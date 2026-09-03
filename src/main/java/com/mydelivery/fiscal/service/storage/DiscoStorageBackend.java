package com.mydelivery.fiscal.service.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Backend padrão — grava XMLs em disco local em
 * {@code /tmp/fiscal-xmls/{cnpj}/{chave}.xml}. Bom pra dev/teste.
 *
 * <p>Em PRODUÇÃO use {@link R2StorageBackend} (Cloudflare R2 é durável,
 * escala e cabe no tier grátis). Disco local vai embora com o restart do
 * container Railway.
 *
 * <p>Ativação: default OU {@code mydelivery.fiscal.storage=local}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mydelivery.fiscal.storage",
        havingValue = "local", matchIfMissing = true)
public class DiscoStorageBackend implements XmlStorageBackend {

    private final String rootDir;

    public DiscoStorageBackend(
            @Value("${mydelivery.fiscal.xml-dir:/tmp/fiscal-xmls}") String rootDir) {
        this.rootDir = rootDir;
        try {
            Files.createDirectories(Paths.get(rootDir));
            log.info("[Fiscal][Storage] Backend=DISCO — XMLs em: {}", rootDir);
            log.warn("[Fiscal][Storage] Disco local em produção NÃO é durável. "
                    + "Migre pra R2: mydelivery.fiscal.storage=r2 + credenciais.");
        } catch (IOException e) {
            log.warn("[Fiscal][Storage] Falha ao criar {}: {}", rootDir, e.getMessage());
        }
    }

    @Override public String nome() { return "disco"; }

    @Override
    public String gravar(String cnpj, String chaveAcesso, String xml) {
        try {
            Path dir = Paths.get(rootDir, sanitizar(cnpj));
            Files.createDirectories(dir);
            Path arq = dir.resolve(sanitizar(chaveAcesso) + ".xml");
            if (Files.exists(arq)) {
                log.warn("[Fiscal][Disco] XML ja existe (nao sobrescreve): {}", arq);
                return arq.toString();
            }
            Files.write(arq, xml.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            return arq.toString();
        } catch (Exception e) {
            log.error("[Fiscal][Disco] Falha ao gravar XML {}: {}", chaveAcesso, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String ler(String cnpj, String chaveAcesso) {
        try {
            Path arq = Paths.get(rootDir, sanitizar(cnpj), sanitizar(chaveAcesso) + ".xml");
            if (!Files.exists(arq)) return null;
            return Files.readString(arq, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[Fiscal][Disco] Falha ao ler XML {}: {}", chaveAcesso, e.getMessage());
            return null;
        }
    }

    // ══ Relatórios pré-gerados pelo cron mensal ══════════════════════════
    @Override
    public String gravarRelatorio(String cnpj, String ym, byte[] bytes) {
        try {
            Path dir = Paths.get(rootDir, sanitizar(cnpj), "_relatorios");
            Files.createDirectories(dir);
            Path arq = dir.resolve(sanitizar(ym) + ".zip");
            Files.write(arq, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return arq.toString();
        } catch (Exception e) {
            log.error("[Fiscal][Disco] gravarRelatorio {}/{}: {}", cnpj, ym, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public byte[] lerRelatorio(String cnpj, String ym) {
        try {
            Path arq = Paths.get(rootDir, sanitizar(cnpj), "_relatorios", sanitizar(ym) + ".zip");
            return Files.exists(arq) ? Files.readAllBytes(arq) : null;
        } catch (Exception e) {
            log.warn("[Fiscal][Disco] lerRelatorio {}/{}: {}", cnpj, ym, e.getMessage());
            return null;
        }
    }

    @Override
    public java.util.List<String> listarRelatorios(String cnpj) {
        try {
            Path dir = Paths.get(rootDir, sanitizar(cnpj), "_relatorios");
            if (!Files.exists(dir)) return java.util.List.of();
            try (var stream = Files.list(dir)) {
                return stream.filter(p -> p.getFileName().toString().endsWith(".zip"))
                        .map(p -> p.getFileName().toString().replace(".zip", ""))
                        .sorted(java.util.Comparator.reverseOrder())
                        .toList();
            }
        } catch (Exception e) {
            log.warn("[Fiscal][Disco] listarRelatorios {}: {}", cnpj, e.getMessage());
            return java.util.List.of();
        }
    }

    private static String sanitizar(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
