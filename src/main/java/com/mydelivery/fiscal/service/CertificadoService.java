package com.mydelivery.fiscal.service;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.fiscal.model.CertificadoDigital;
import com.mydelivery.fiscal.repository.CertificadoDigitalRepository;
import com.mydelivery.model.Restaurante;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gerenciamento do certificado A1 do restaurante — upload, validação,
 * substituição. Ao subir, valida:
 * <ol>
 *   <li>PFX abre com a senha (senha correta).</li>
 *   <li>CNPJ do certificado bate com o CNPJ do restaurante logado (evita
 *       um dono subir cert de outra empresa).</li>
 *   <li>Certificado não está expirado.</li>
 * </ol>
 * Só depois de validado grava no cofre criptografado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificadoService {

    private final CertificadoDigitalRepository certRepo;
    private final CofreCertificadoService cofre;
    private final AuditoriaFiscalService auditoria;

    // CN típico: "NOME COMPLETO:12345678000199" — tira os 14 dígitos do fim.
    private static final Pattern CNPJ_NO_CN = Pattern.compile(":?(\\d{14})");

    public record ResultadoUpload(String cnpj, String nomeTitular,
                                   LocalDateTime validoAte, String fingerprint) {}

    /**
     * Sobe/substitui o certificado do restaurante.
     * @param cnpjEsperado CNPJ do restaurante logado — o cert TEM que bater
     * @param pfxBytes bytes do arquivo .pfx que o dono enviou
     * @param senha senha do PFX (fornecida pelo dono/contador)
     */
    @Transactional
    public ResultadoUpload subirCertificado(Restaurante r, String cnpjEsperado,
                                            byte[] pfxBytes, String senha,
                                            String usuarioEmail, String ipOrigem) {
        cofre.verificarConfig();
        if (pfxBytes == null || pfxBytes.length < 100) {
            throw new IllegalArgumentException("Arquivo .pfx vazio ou inválido");
        }
        if (senha == null || senha.isEmpty()) {
            throw new IllegalArgumentException("Senha do certificado é obrigatória");
        }

        // ── 1. Abre o PFX ── (se senha errada, throws BadPaddingException)
        KeyStore ks;
        X509Certificate cert;
        try {
            ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(pfxBytes), senha.toCharArray());
            cert = extrairCertificadoPrincipal(ks);
        } catch (Exception e) {
            auditoria.registrar(r.getId(), cnpjEsperado, usuarioEmail,
                    "CERT_UPLOAD", "FALHA", ipOrigem,
                    Map.of("motivo", "PFX invalido ou senha errada",
                           "erro", e.getMessage()));
            throw new RuntimeException("Não foi possível abrir o certificado. "
                    + "Confira o arquivo .pfx e a senha e tente novamente.");
        }

        // ── 2. Extrai CNPJ do subject e valida ──
        String cnpjDoCert = extrairCnpjDoCertificado(cert);
        String cnpjLimpo = somenteDigitos(cnpjEsperado);
        if (cnpjDoCert == null || cnpjLimpo == null || !cnpjDoCert.equals(cnpjLimpo)) {
            auditoria.registrar(r.getId(), cnpjEsperado, usuarioEmail,
                    "CERT_UPLOAD", "NEGADO", ipOrigem,
                    Map.of("motivo", "CNPJ do certificado nao bate com CNPJ do restaurante",
                           "cnpjRestaurante", mascararCnpj(cnpjLimpo),
                           "cnpjCert", mascararCnpj(cnpjDoCert)));
            throw new RuntimeException("O certificado é de outro CNPJ ("
                    + mascararCnpj(cnpjDoCert) + "). Use o certificado do CNPJ desta loja ("
                    + mascararCnpj(cnpjLimpo) + ").");
        }

        // ── 3. Valida validade ──
        try { cert.checkValidity(); } catch (Exception e) {
            auditoria.registrar(r.getId(), cnpjEsperado, usuarioEmail,
                    "CERT_UPLOAD", "NEGADO", ipOrigem,
                    Map.of("motivo", "Certificado expirado ou ainda nao valido"));
            throw new RuntimeException("Certificado expirado ou fora do prazo de validade.");
        }

        // ── 4. Criptografa e grava (envelope encryption por CNPJ) ──
        CofreCertificadoService.Cifrado pfxCif = cofre.criptografar(pfxBytes, cnpjLimpo);
        CofreCertificadoService.Cifrado senhaCif = cofre.criptografar(senha, cnpjLimpo);
        String fingerprint;
        try { fingerprint = sha256Hex(cert.getEncoded()); }
        catch (Exception e) { fingerprint = "err"; }
        LocalDateTime validoAte = cert.getNotAfter().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
        String nomeTitular = cert.getSubjectX500Principal().getName();

        // Marca cert anterior como inativo (histórico)
        certRepo.findByRestauranteIdAndAtivoTrue(r.getId()).ifPresent(antigo -> {
            antigo.setAtivo(false);
            certRepo.save(antigo);
        });

        CertificadoDigital novo = CertificadoDigital.builder()
                .restaurante(r).cnpj(cnpjLimpo).nomeTitular(nomeTitular)
                .pfxCiphertext(pfxCif.ciphertext()).pfxIv(pfxCif.iv()).pfxTag(pfxCif.tag())
                .senhaCiphertext(senhaCif.ciphertext()).senhaIv(senhaCif.iv()).senhaTag(senhaCif.tag())
                .validoAte(validoAte).fingerprintSha256(fingerprint).ativo(true)
                .build();
        certRepo.save(novo);

        Map<String, Object> det = new LinkedHashMap<>();
        det.put("fingerprint", fingerprint);
        det.put("validoAte", validoAte.toString());
        det.put("nomeTitular", nomeTitular);
        auditoria.registrar(r.getId(), cnpjLimpo, usuarioEmail, "CERT_UPLOAD", "OK", ipOrigem, det);

        log.info("[Fiscal][Cert] Restaurante {} — novo certificado ativo, valido ate {}",
                r.getId(), validoAte);
        return new ResultadoUpload(cnpjLimpo, nomeTitular, validoAte, fingerprint);
    }

    /** Status pro painel — SEM expor dado sensível. */
    public Map<String, Object> statusCertificado(Long restauranteId) {
        Optional<CertificadoDigital> op = certRepo.findByRestauranteIdAndAtivoTrue(restauranteId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("temCertificado", op.isPresent());
        op.ifPresent(c -> {
            out.put("nomeTitular", c.getNomeTitular());
            out.put("validoAte", c.getValidoAte().toString());
            long diasRest = java.time.Duration.between(
                    LocalDateTime.now(), c.getValidoAte()).toDays();
            out.put("diasRestantes", diasRest);
            out.put("expirado", diasRest < 0);
            out.put("proximoVencer", diasRest >= 0 && diasRest <= 30);
            out.put("fingerprint", c.getFingerprintSha256());
            out.put("cnpj", mascararCnpj(c.getCnpj()));
            out.put("atualizadoEm", c.getAtualizadoEm() == null ? null : c.getAtualizadoEm().toString());
        });
        return out;
    }

    /**
     * Descriptografa PFX + senha pra uso interno (emissão de nota). Só o
     * módulo fiscal chama isso. NUNCA expor via API.
     */
    public record PfxDescriptografado(byte[] pfx, String senha) {}

    public PfxDescriptografado abrirParaUso(Long restauranteId, String usuarioEmail, String ipOrigem) {
        CertificadoDigital c = certRepo.findByRestauranteIdAndAtivoTrue(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante nao tem certificado ativo"));
        byte[] pfx = cofre.descriptografar(c.getPfxCiphertext(), c.getPfxIv(), c.getPfxTag(), c.getCnpj());
        String senha = cofre.descriptografarString(
                c.getSenhaCiphertext(), c.getSenhaIv(), c.getSenhaTag(), c.getCnpj());
        auditoria.registrar(restauranteId, c.getCnpj(), usuarioEmail,
                "CERT_USO_INTERNO", "OK", ipOrigem,
                Map.of("fingerprint", c.getFingerprintSha256()));
        return new PfxDescriptografado(pfx, senha);
    }

    // ─── HELPERS ─────────────────────────────────────────────────────────

    private X509Certificate extrairCertificadoPrincipal(KeyStore ks) throws Exception {
        Enumeration<String> aliases = ks.aliases();
        for (String alias : Collections.list(aliases)) {
            java.security.cert.Certificate c = ks.getCertificate(alias);
            if (c instanceof X509Certificate x) return x;
        }
        throw new RuntimeException("PFX sem certificado X.509");
    }

    /**
     * Extrai o CNPJ do subject do certificado. Formatos comuns emitidos por
     * ACs credenciadas (Serasa, Certisign, Valid, Soluti, Digital Sign...):
     *   CN=NOME EMPRESA:12345678000199
     *   CN=NOME DA PESSOA:12345678900   (e-CPF, não serve)
     */
    private static String extrairCnpjDoCertificado(X509Certificate cert) {
        String subject = cert.getSubjectX500Principal().getName();
        Matcher m = CNPJ_NO_CN.matcher(subject);
        while (m.find()) {
            String d = m.group(1);
            if (d.length() == 14) return d;   // pega 14 dígitos (CNPJ, não CPF)
        }
        return null;
    }

    private static String somenteDigitos(String s) {
        if (s == null) return null;
        return s.replaceAll("\\D", "");
    }

    private static String mascararCnpj(String c) {
        if (c == null || c.length() < 8) return "***";
        return c.substring(0, 2) + ".***.***/****-" + c.substring(c.length() - 2);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) { return "err"; }
    }
}
