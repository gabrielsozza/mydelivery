package com.mydelivery.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.mydelivery.model.Restaurante;
import com.mydelivery.model.SuporteAnexo;
import com.mydelivery.model.SuporteMensagem;
import com.mydelivery.model.SuporteTicket;
import com.mydelivery.repository.SuporteAnexoRepository;
import com.mydelivery.repository.SuporteTicketRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Lógica de negócio do suporte: criação de tickets, mensagens, upload de anexos.
 *
 * Storage: Cloudinary (não filesystem local).
 *
 * Segurança de upload (camadas):
 *  1. Tamanho máximo por arquivo (10MB no CloudinaryService).
 *  2. Quantidade de anexos por mensagem (configurável, default 3).
 *  3. Lista permitida de extensões (JPG/JPEG/PNG/PDF/HEIC) + MIME.
 *  4. Magic bytes — lê os primeiros bytes do stream antes de enviar pro Cloudinary.
 *     Impede renomear .exe como .png (extensão e MIME podem ser falsificados,
 *     mas o cabeçalho binário não).
 *  5. Cloudinary armazena com public_id randomizado dentro de suporte/ticket-{id}/.
 *
 * Throttle: in-memory por restaurante. Reset no restart é OK.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuporteService {

    @Value("${mydelivery.suporte.throttle-segundos:3}")
    private int throttleSegundos;

    @Value("${mydelivery.suporte.max-anexos:3}")
    private int maxAnexos;

    private final SuporteTicketRepository ticketRepository;
    private final SuporteAnexoRepository anexoRepository;
    private final CloudinaryService cloudinaryService;

    /** Última mensagem enviada por restaurante — controle de throttle. */
    private final Map<Long, LocalDateTime> ultimaMsgPorRestaurante = new ConcurrentHashMap<>();

    /**
     * Cria novo ticket com a primeira mensagem (e anexos opcionais).
     * Gera automaticamente mensagem de boas-vindas do SISTEMA logo após.
     */
    @Transactional
    public SuporteTicket criarTicket(Restaurante r, String assunto, String textoInicial, MultipartFile[] anexos) {
        validarThrottle(r.getId());
        if (textoInicial == null || textoInicial.isBlank()) {
            throw new RuntimeException("Descreva o problema antes de enviar.");
        }

        SuporteTicket ticket = SuporteTicket.builder()
                .restaurante(r)
                .assunto(assunto != null && !assunto.isBlank() ? assunto.trim() : "Atendimento")
                .status(SuporteTicket.Status.AGUARDANDO)
                .categoria(sugerirCategoria(textoInicial))
                .build();
        ticket = ticketRepository.save(ticket);

        // Mensagem do restaurante
        criarMensagemInterna(ticket, SuporteMensagem.Autor.RESTAURANTE, r.getNome(), textoInicial, anexos);

        // Mensagem automática do sistema (acknowledge) — sempre vem em seguida
        criarMensagemInterna(ticket, SuporteMensagem.Autor.SISTEMA, "MyDelivery",
                "Recebemos sua solicitação 👋\nEstamos transferindo você para um atendente. O tempo médio de resposta é de algumas horas em dia útil.",
                null);

        marcarThrottle(r.getId());
        return ticket;
    }

    /** Adiciona mensagem a um ticket existente. Tenant validado pelo caller. */
    @Transactional
    public SuporteMensagem responder(SuporteTicket ticket, Restaurante r, String texto, MultipartFile[] anexos) {
        validarThrottle(r.getId());
        boolean temAnexo = anexos != null && anexos.length > 0
                && java.util.Arrays.stream(anexos).anyMatch(a -> a != null && !a.isEmpty());
        if ((texto == null || texto.isBlank()) && !temAnexo) {
            throw new RuntimeException("Mensagem vazia");
        }
        // Texto opcional quando tem anexo — guarda string vazia pra preservar a linha no banco.
        if (texto == null) texto = "";
        // Reabrir se estava resolvido — restaurante mandando msg deixa em AGUARDANDO
        if (ticket.getStatus() == SuporteTicket.Status.RESOLVIDO
                || ticket.getStatus() == SuporteTicket.Status.FECHADO) {
            ticket.setStatus(SuporteTicket.Status.AGUARDANDO);
            ticket.setResolvidoEm(null);
            ticketRepository.save(ticket);
        }
        SuporteMensagem msg = criarMensagemInterna(ticket, SuporteMensagem.Autor.RESTAURANTE,
                r.getNome(), texto, anexos);
        marcarThrottle(r.getId());
        return msg;
    }

    /**
     * Apenas pra anexos LEGADOS (filesystem antigo). Anexos novos vivem no
     * Cloudinary e são acessados via redirect no controller (SuporteAnexo.url).
     *
     * Mantido pra compatibilidade com anexos pré-migração que ainda estão em disco.
     */
    public AnexoDownload abrirAnexoLegado(SuporteAnexo anexo) {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("./uploads/suporte",
                    String.valueOf(anexo.getMensagem().getTicket().getId()),
                    anexo.getNomeArquivo());
            return new AnexoDownload(java.nio.file.Files.newInputStream(path),
                    anexo.getNomeOriginal(),
                    anexo.getMimeType(),
                    anexo.getTamanhoBytes());
        } catch (IOException e) {
            throw new RuntimeException("Arquivo não encontrado");
        }
    }

    // ── internos ──

    private SuporteMensagem criarMensagemInterna(SuporteTicket ticket, SuporteMensagem.Autor autor,
                                                  String autorNome, String texto, MultipartFile[] arquivos) {
        SuporteMensagem msg = SuporteMensagem.builder()
                .ticket(ticket)
                .autor(autor)
                .autorNome(autorNome)
                .texto(texto)
                .build();
        ticket.getMensagens().add(msg);

        // Persiste o ticket ANTES de tentar criar anexos. Cascade.ALL faz a mensagem
        // virar managed com ID — sem isso, anexoRepository.save() abaixo dispara
        // TransientPropertyValueException porque a mensagem referenciada ainda é transient.
        ticketRepository.save(ticket);

        if (arquivos != null && arquivos.length > 0) {
            if (arquivos.length > maxAnexos) {
                throw new RuntimeException("Máximo de " + maxAnexos + " anexos por mensagem.");
            }
            for (MultipartFile f : arquivos) {
                if (f == null || f.isEmpty()) continue;
                SuporteAnexo anexo = salvarAnexo(msg, ticket.getId(), f);
                msg.getAnexos().add(anexo);
            }
        }
        return msg;
    }

    /**
     * Validação em 3 camadas + upload para Cloudinary.
     * Backend NÃO toca o disco — recebe multipart, valida, envia ao Cloudinary
     * e persiste a URL retornada.
     */
    private SuporteAnexo salvarAnexo(SuporteMensagem msg, Long ticketId, MultipartFile f) {
        String nomeOriginal = f.getOriginalFilename() == null ? "arquivo" : f.getOriginalFilename();
        String ext = extrairExtensao(nomeOriginal).toLowerCase(Locale.ROOT);

        // Camada 1: extensão na whitelist
        if (!EXT_PERMITIDAS.contains(ext)) {
            throw new RuntimeException("Tipo de arquivo não permitido: ." + ext
                    + ". Use JPG, PNG, PDF ou HEIC.");
        }

        // Camada 2: MIME informado pelo browser
        String mime = f.getContentType();
        if (mime != null && !mime.isBlank() && !mimeCompativel(mime, ext)) {
            throw new RuntimeException("Tipo MIME incompatível com a extensão.");
        }

        // Camada 3: magic bytes (fonte da verdade — não pode ser falsificado por rename)
        byte[] header = new byte[16];
        try (InputStream is = f.getInputStream()) {
            int lidos = is.readNBytes(header, 0, 16);
            if (lidos < 4 || !magicBytesOk(header, ext)) {
                throw new RuntimeException("Arquivo corrompido ou não é " + ext.toUpperCase() + " válido.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Falha ao ler o arquivo");
        }

        // Cloudinary upload — pasta organizada por ticket
        String url = cloudinaryService.uploadArquivo(f, "suporte/ticket-" + ticketId);

        SuporteAnexo anexo = SuporteAnexo.builder()
                .mensagem(msg)
                .nomeOriginal(nomeOriginal)
                .nomeArquivo(url) // mantemos a URL aqui também por compat com queries antigas
                .url(url)
                .mimeType(mime != null ? mime : mimeDeExt(ext))
                .tamanhoBytes(f.getSize())
                .build();
        return anexoRepository.save(anexo);
    }

    private void validarThrottle(Long restauranteId) {
        LocalDateTime ult = ultimaMsgPorRestaurante.get(restauranteId);
        if (ult != null && Duration.between(ult, LocalDateTime.now()).toSeconds() < throttleSegundos) {
            throw new RuntimeException("Aguarde " + throttleSegundos + "s entre mensagens.");
        }
    }
    private void marcarThrottle(Long restauranteId) {
        ultimaMsgPorRestaurante.put(restauranteId, LocalDateTime.now());
    }

    private String sugerirCategoria(String texto) {
        String t = texto.toLowerCase(Locale.ROOT);
        if (t.contains("pix") || t.contains("cart") || t.contains("pagamento")) return "pagamento";
        if (t.contains("whatsapp") || t.contains("bot") || t.contains("assistente")) return "whatsapp";
        if (t.contains("cardap") || t.contains("produto") || t.contains("categoria")) return "cardapio";
        if (t.contains("entrega") || t.contains("taxa") || t.contains("frete")) return "entrega";
        if (t.contains("plano") || t.contains("assinatura") || t.contains("cobran")) return "assinatura";
        return "geral";
    }

    private String extrairExtensao(String nome) {
        int i = nome.lastIndexOf('.');
        if (i < 0 || i == nome.length() - 1) return "";
        return nome.substring(i + 1);
    }

    // ── Validação de tipos ──

    private static final List<String> EXT_PERMITIDAS = List.of("jpg", "jpeg", "png", "pdf", "heic");

    private boolean mimeCompativel(String mime, String ext) {
        mime = mime.toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "jpg", "jpeg" -> mime.equals("image/jpeg") || mime.equals("image/jpg");
            case "png" -> mime.equals("image/png");
            case "pdf" -> mime.equals("application/pdf");
            case "heic" -> mime.equals("image/heic") || mime.equals("image/heif")
                    || mime.equals("application/octet-stream"); // alguns browsers não reconhecem
            default -> false;
        };
    }

    private String mimeDeExt(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "pdf" -> "application/pdf";
            case "heic" -> "image/heic";
            default -> "application/octet-stream";
        };
    }

    /**
     * Magic bytes (cabeçalho binário) — fonte da verdade do tipo do arquivo.
     *  JPG:  FF D8 FF
     *  PNG:  89 50 4E 47 0D 0A 1A 0A
     *  PDF:  25 50 44 46 2D ("%PDF-")
     *  HEIC: bytes 4-7 = "ftyp"  +  bytes 8-11 ∈ {heic, heix, hevc, mif1, msf1, ...}
     */
    private boolean magicBytesOk(byte[] h, String ext) {
        if (h == null || h.length < 4) return false;
        switch (ext) {
            case "jpg", "jpeg":
                return (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
            case "png":
                return h[0] == (byte) 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
            case "pdf":
                return h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F';
            case "heic":
                if (h.length < 12) return false;
                if (h[4] != 'f' || h[5] != 't' || h[6] != 'y' || h[7] != 'p') return false;
                String brand = new String(h, 8, 4);
                return brand.equals("heic") || brand.equals("heix") || brand.equals("hevc")
                        || brand.equals("hevx") || brand.equals("mif1") || brand.equals("msf1")
                        || brand.equals("heim") || brand.equals("heis");
            default:
                return false;
        }
    }

    /** Wrapper de download — fechado pelo controller após stream. */
    public record AnexoDownload(InputStream stream, String nomeOriginal, String mimeType, Long tamanhoBytes) {}
}
