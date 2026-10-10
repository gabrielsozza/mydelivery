package com.mydelivery.fiscal.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.mydelivery.fiscal.model.NotaEntradaAnexo;
import com.mydelivery.fiscal.repository.NotaEntradaAnexoRepository;
import com.mydelivery.model.Restaurante;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotaEntradaAnexoService {
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private final NotaEntradaAnexoRepository repo;

    @Transactional
    public NotaEntradaAnexo salvar(Restaurante restaurante, MultipartFile arquivo) throws java.io.IOException {
        if (arquivo == null || arquivo.isEmpty() || arquivo.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Selecione uma foto ou PDF de até 10 MB.");
        }
        byte[] bytes = arquivo.getBytes();
        String mime = detectarTipo(bytes);
        String nome = limparNome(arquivo.getOriginalFilename(), mime);
        String sha = sha256(bytes);
        return repo.findByRestauranteIdAndSha256(restaurante.getId(), sha)
                .orElseGet(() -> repo.save(NotaEntradaAnexo.builder()
                        .restaurante(restaurante)
                        .nomeArquivo(nome)
                        .tipoMime(mime)
                        .tamanho((long) bytes.length)
                        .sha256(sha)
                        .conteudo(bytes)
                        .build()));
    }

    /** Inspeciona a assinatura real: extensão e Content-Type enviados pelo navegador não são confiáveis. */
    public static String detectarTipo(byte[] b) {
        if (b == null || b.length < 12) throw formatoInvalido();
        if ((b[0] & 255) == 0xFF && (b[1] & 255) == 0xD8 && (b[2] & 255) == 0xFF)
            return "image/jpeg";
        if ((b[0] & 255) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 255) == 0x0D && (b[5] & 255) == 0x0A)
            return "image/png";
        if (ascii(b, 0, "RIFF") && ascii(b, 8, "WEBP")) return "image/webp";
        if (ascii(b, 0, "%PDF-")) return "application/pdf";
        if (ascii(b, 4, "ftyp")) {
            String brand = new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)
                    .toLowerCase(Locale.ROOT);
            if (brand.equals("heic") || brand.equals("heix") || brand.equals("hevc")
                    || brand.equals("hevx") || brand.equals("mif1") || brand.equals("msf1"))
                return "image/heic";
        }
        throw formatoInvalido();
    }

    private static boolean ascii(byte[] b, int offset, String text) {
        if (b.length < offset + text.length()) return false;
        for (int i = 0; i < text.length(); i++)
            if (b[offset + i] != (byte) text.charAt(i)) return false;
        return true;
    }

    private static IllegalArgumentException formatoInvalido() {
        return new IllegalArgumentException("Formato não aceito. Envie JPG, PNG, WEBP, HEIC ou PDF.");
    }

    private static String limparNome(String original, String mime) {
        String ext = Map.of("image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp",
                "image/heic", ".heic", "application/pdf", ".pdf").get(mime);
        String nome = original == null ? "" : original.replace('\\', '/');
        nome = nome.substring(nome.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
        if (nome.isBlank()) nome = "nota-entrada";
        nome = nome.replaceFirst("(?i)\\.(jpe?g|jfif|png|webp|heic|heif|pdf)$", "");
        if (nome.length() > 180) nome = nome.substring(0, 180);
        return nome + ext;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
