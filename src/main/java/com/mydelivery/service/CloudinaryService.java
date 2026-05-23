package com.mydelivery.service;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.mydelivery.config.CloudinaryConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Upload de imagens pro Cloudinary.
 *
 * Validação:
 *  - Extensão whitelist (jpg/jpeg/png/webp/heic)
 *  - Tamanho máximo 5MB
 *  - Cloudinary detecta tipo real via header automaticamente — proteção extra
 *
 * Retorna a `secure_url` que é o que persistimos em Produto.fotoUrl.
 * Em caso de falta de configuração, RuntimeException clara — caller mostra
 * mensagem amigável ao usuário sem expor detalhes técnicos.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;
    private final CloudinaryConfig config;

    @Value("${mydelivery.cloudinary.folder:mydelivery/produtos}")
    private String folder;

    /**
     * Upload de imagem (JPG/PNG/WEBP/HEIC) — aplica otimizações automáticas.
     * Usado pra fotos de produtos, logo e capa de restaurante.
     */
    public String upload(MultipartFile file, String subfolder) {
        return uploadInterno(file, subfolder, 5L * 1024 * 1024,
                "jpg|jpeg|png|webp|heic",
                /* otimizar imagem */ true,
                /* Use JPG, PNG, WEBP ou HEIC. */ "Use JPG, PNG, WEBP ou HEIC.");
    }

    /**
     * Upload de qualquer arquivo permitido (imagem ou PDF). Usado em anexos
     * de suporte. Tamanho permitido até 10MB.
     */
    public String uploadArquivo(MultipartFile file, String subfolder) {
        return uploadInterno(file, subfolder, 10L * 1024 * 1024,
                "jpg|jpeg|png|webp|heic|pdf",
                /* PDF não otimizar */ false,
                "Use JPG, PNG, WEBP, HEIC ou PDF.");
    }

    /**
     * Upload direto de bytes (sem MultipartFile) — usado pela importação de
     * cardápio, que baixa a imagem da URL origem e re-hospeda no Cloudinary.
     * Não valida extensão por nome de arquivo (a URL fonte pode não ter).
     * resource_type=auto deixa o Cloudinary detectar pelo content-type.
     */
    public String uploadBytes(byte[] bytes, String subfolder) {
        if (!config.isConfigured()) {
            log.warn("[Cloudinary] uploadBytes: credenciais não configuradas — devolvendo null");
            return null;
        }
        if (bytes == null || bytes.length == 0) return null;
        if (bytes.length > 8L * 1024 * 1024) {
            log.warn("[Cloudinary] uploadBytes: imagem >8MB descartada");
            return null;
        }
        String pasta = folder + (subfolder != null && !subfolder.isBlank() ? "/" + subfolder : "");
        @SuppressWarnings("unchecked")
        Map<String, Object> opts = ObjectUtils.asMap(
                "folder", pasta,
                "resource_type", "image",
                "quality", "auto:good",
                "fetch_format", "auto",
                "unique_filename", true);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resultado = cloudinary.uploader().upload(bytes, opts);
            Object url = resultado.get("secure_url");
            return url == null ? null : url.toString();
        } catch (Exception e) {
            log.warn("[Cloudinary] uploadBytes falhou: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Implementação interna comum. resource_type=auto permite Cloudinary
     * detectar imagem/PDF/etc. automaticamente — usamos isso pra suportar
     * documentos sem precisar de endpoint separado.
     */
    private String uploadInterno(MultipartFile file, String subfolder, long maxBytes,
                                  String regexExtensoes, boolean otimizar, String msgErroTipo) {
        if (!config.isConfigured()) {
            log.error("[Cloudinary] Credenciais não configuradas — defina CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY e CLOUDINARY_API_SECRET.");
            throw new RuntimeException("Serviço de imagens indisponível. Avise o administrador.");
        }
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("Arquivo vazio.");
        }
        if (file.getSize() > maxBytes) {
            throw new RuntimeException("Arquivo maior que " + (maxBytes / 1024 / 1024) + "MB.");
        }

        String nome = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = nome.toLowerCase(Locale.ROOT);
        int dot = ext.lastIndexOf('.');
        ext = dot >= 0 ? ext.substring(dot + 1) : "";
        if (!ext.matches(regexExtensoes)) {
            throw new RuntimeException(msgErroTipo);
        }

        String pasta = folder + (subfolder != null && !subfolder.isBlank() ? "/" + subfolder : "");

        // Opções base — resource_type=auto deixa Cloudinary detectar imagem vs raw (PDF, etc).
        @SuppressWarnings("unchecked")
        Map<String, Object> opts = ObjectUtils.asMap(
                "folder", pasta,
                "resource_type", "auto",
                "overwrite", true,
                "use_filename", true,
                "unique_filename", true);
        if (otimizar && !"pdf".equals(ext)) {
            opts.put("quality", "auto:good");
            opts.put("fetch_format", "auto");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resultado = cloudinary.uploader().upload(file.getBytes(), opts);
            Object url = resultado.get("secure_url");
            if (url == null) {
                throw new RuntimeException("Cloudinary não retornou URL");
            }
            log.info("[Cloudinary] Upload OK — pasta={}, publicId={}, type={}", pasta,
                    resultado.get("public_id"), resultado.get("resource_type"));
            return url.toString();
        } catch (IOException e) {
            log.error("[Cloudinary] Falha no upload: {}", e.getMessage());
            throw new RuntimeException("Falha ao enviar arquivo.");
        }
    }
}
