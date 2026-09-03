package com.mydelivery.fiscal.service.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Backend de produção — Cloudflare R2 via S3 SDK (R2 é S3-compatible).
 *
 * <h3>Tier grátis</h3>
 * 10 GB de storage + 1M leituras + 10M escritas/mês + <b>zero taxa de egress</b>.
 * Cabe milhões de XMLs (cada NFC-e tem ~5-15 KB).
 *
 * <h3>Setup no Cloudflare</h3>
 * <ol>
 *   <li>cloudflare.com → R2 → Create bucket (ex: {@code mydelivery-fiscal-xmls})</li>
 *   <li>R2 → Manage R2 API tokens → Create → permissão Object Read & Write no bucket</li>
 *   <li>Copiar Access Key ID + Secret Access Key + Endpoint (formato
 *       {@code https://<accountid>.r2.cloudflarestorage.com})</li>
 * </ol>
 *
 * <h3>Variáveis no Railway</h3>
 * <pre>
 * FISCAL_STORAGE=r2
 * FISCAL_R2_ENDPOINT=https://xxxxx.r2.cloudflarestorage.com
 * FISCAL_R2_BUCKET=mydelivery-fiscal-xmls
 * FISCAL_R2_ACCESS_KEY=xxx
 * FISCAL_R2_SECRET_KEY=xxx
 * </pre>
 *
 * <h3>Segurança</h3>
 * <ul>
 *   <li>Bucket <b>privado</b> (não expor listagem pública)</li>
 *   <li>Objetos com Content-Type application/xml</li>
 *   <li>Path por CNPJ ({@code {cnpj}/{chave}.xml}) — isolamento entre lojas</li>
 *   <li>Nunca sobrescreve — se chave já existe, mantém original</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mydelivery.fiscal.storage", havingValue = "r2")
public class R2StorageBackend implements XmlStorageBackend {

    private final S3Client s3;
    private final String bucket;

    public R2StorageBackend(
            @Value("${mydelivery.fiscal.r2.endpoint:${FISCAL_R2_ENDPOINT:}}") String endpoint,
            @Value("${mydelivery.fiscal.r2.bucket:${FISCAL_R2_BUCKET:}}") String bucket,
            @Value("${mydelivery.fiscal.r2.access-key:${FISCAL_R2_ACCESS_KEY:}}") String accessKey,
            @Value("${mydelivery.fiscal.r2.secret-key:${FISCAL_R2_SECRET_KEY:}}") String secretKey) {
        if (endpoint == null || endpoint.isBlank()
                || bucket == null || bucket.isBlank()
                || accessKey == null || accessKey.isBlank()
                || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "R2 configurado como storage mas credenciais faltando. "
                  + "Defina FISCAL_R2_ENDPOINT, FISCAL_R2_BUCKET, "
                  + "FISCAL_R2_ACCESS_KEY, FISCAL_R2_SECRET_KEY no Railway.");
        }
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                // HTTP client explícito — sem isso o SDK procura ApacheHttpClient
                // no classpath e crasha se não achar (ClassNotFoundException).
                // UrlConnectionHttpClient é leve (HttpURLConnection puro) e
                // suficiente pro perfil de uso do R2 (PUT/GET/HEAD de XMLs).
                .httpClient(UrlConnectionHttpClient.builder().build())
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))            // R2 aceita "auto"
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true) // R2 usa path-style
                        .build())
                .build();
        log.info("[Fiscal][Storage] Backend=R2 bucket={} endpoint={}", bucket, endpoint);
    }

    @Override public String nome() { return "r2"; }

    @Override
    public String gravar(String cnpj, String chaveAcesso, String xml) {
        try {
            String key = sanitizar(cnpj) + "/" + sanitizar(chaveAcesso) + ".xml";
            // Não sobrescreve — se já existe, retorna a mesma URL sem gravar.
            if (existe(key)) {
                log.warn("[Fiscal][R2] Objeto ja existe (nao sobrescreve): {}", key);
                return "r2://" + bucket + "/" + key;
            }
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket).key(key)
                    .contentType("application/xml")
                    .build(),
                RequestBody.fromString(xml, StandardCharsets.UTF_8));
            return "r2://" + bucket + "/" + key;
        } catch (S3Exception e) {
            log.error("[Fiscal][R2] Falha ao gravar {}/{}: {} ({})",
                    cnpj, chaveAcesso, e.awsErrorDetails().errorCode(), e.awsErrorDetails().errorMessage());
            return null;
        } catch (Exception e) {
            log.error("[Fiscal][R2] Exception gravando XML {}/{}: {}", cnpj, chaveAcesso, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String ler(String cnpj, String chaveAcesso) {
        try {
            String key = sanitizar(cnpj) + "/" + sanitizar(chaveAcesso) + ".xml";
            ResponseBytes<GetObjectResponse> res = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key).build());
            return res.asUtf8String();
        } catch (NoSuchKeyException e) {
            return null;
        } catch (Exception e) {
            log.warn("[Fiscal][R2] Falha ao ler {}/{}: {}", cnpj, chaveAcesso, e.getMessage());
            return null;
        }
    }

    private boolean existe(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) { return false; }
        catch (Exception e) { return false; }
    }

    // ══ Relatórios pré-gerados pelo cron mensal ══════════════════════════
    @Override
    public String gravarRelatorio(String cnpj, String ym, byte[] bytes) {
        try {
            String key = sanitizar(cnpj) + "/_relatorios/" + sanitizar(ym) + ".zip";
            // Sobrescreve OK — se rodar de novo, atualiza o ZIP mensal.
            s3.putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                    .bucket(bucket).key(key)
                    .contentType("application/zip")
                    .build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
            return "r2://" + bucket + "/" + key;
        } catch (Exception e) {
            log.error("[Fiscal][R2] gravarRelatorio {}/{}: {}", cnpj, ym, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public byte[] lerRelatorio(String cnpj, String ym) {
        try {
            String key = sanitizar(cnpj) + "/_relatorios/" + sanitizar(ym) + ".zip";
            var res = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
            return res.asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        } catch (Exception e) {
            log.warn("[Fiscal][R2] lerRelatorio {}/{}: {}", cnpj, ym, e.getMessage());
            return null;
        }
    }

    @Override
    public java.util.List<String> listarRelatorios(String cnpj) {
        try {
            String prefix = sanitizar(cnpj) + "/_relatorios/";
            var lista = s3.listObjectsV2(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).maxKeys(60).build());
            java.util.List<String> out = new java.util.ArrayList<>();
            for (var o : lista.contents()) {
                String k = o.key().substring(prefix.length()).replace(".zip", "");
                out.add(k);
            }
            out.sort(java.util.Comparator.reverseOrder());
            return out;
        } catch (Exception e) {
            log.warn("[Fiscal][R2] listarRelatorios {}: {}", cnpj, e.getMessage());
            return java.util.List.of();
        }
    }

    private static String sanitizar(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
