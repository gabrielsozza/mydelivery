package com.mydelivery.service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.email.remetente}")
    private String remetente;

    @Value("${app.email.nome-remetente}")
    private String nomeRemetente;

    @Value("${app.url}")
    private String appUrl;

    @Value("${mydelivery.resend.api-key:}")
    private String resendApiKey;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Envia email de recuperação de senha.
     * Tenta primeiro via Resend HTTP API (se RESEND_API_KEY setada) — Gmail SMTP
     * é frequentemente bloqueado em IPs cloud, Resend não tem essa restrição.
     * Cai pra SMTP se Resend não configurado.
     */
    @Async
    public void enviarRecuperacaoSenha(String destinatario, String nome, String token) {
        log.info("[Email] iniciando envio recuperação senha → {}", destinatario);
        String link = appUrl + "/redefinir-senha.html?token=" + token;
        String html = montarHtmlRecuperacao(nome, link);

        // Caminho preferido: Resend HTTP API
        if (resendApiKey != null && !resendApiKey.isBlank()) {
            if (enviarViaResend(destinatario, "MyDelivery — Recuperação de senha", html)) return;
            log.warn("[Email] Resend falhou, caindo pra SMTP como fallback");
        }

        // Fallback: SMTP (vidroforte usa)
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(remetente, nomeRemetente);
            helper.setTo(destinatario);
            helper.setSubject("MyDelivery — Recuperação de senha");
            helper.setText(html, true);
            mailSender.send(message);
            log.info("[Email] ✅ recuperação senha enviada via SMTP → {}", destinatario);
        } catch (Exception e) {
            log.error("[Email] ❌ FALHA SMTP → {} | erro: {} ({})",
                    destinatario, e.getMessage(), e.getClass().getSimpleName(), e);
        }
    }

    /** Envia via Resend HTTP API. Retorna true se 2xx. */
    private boolean enviarViaResend(String to, String subject, String html) {
        try {
            // Resend exige domínio verificado pra "from" próprio.
            // Enquanto não verificar, usa "onboarding@resend.dev" (funciona out-of-the-box).
            String from = nomeRemetente + " <onboarding@resend.dev>";
            Map<String, Object> body = Map.of(
                "from", from,
                "to", List.of(to),
                "subject", subject,
                "html", html
            );
            String json = MAPPER.writeValueAsString(body);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.resend.com/emails"))
                .header("Authorization", "Bearer " + resendApiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                log.info("[Email] ✅ enviado via Resend → {} (status {})", to, res.statusCode());
                return true;
            }
            log.error("[Email] ❌ Resend retornou {}: {}", res.statusCode(), res.body());
            return false;
        } catch (Exception e) {
            log.error("[Email] ❌ Resend HTTP erro: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return false;
        }
    }

    /** Template HTML do email de recuperação (compartilhado entre Resend e SMTP). */
    private String montarHtmlRecuperacao(String nome, String link) {
        return """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                <h2 style="color: #FF6B00;">MyDelivery</h2>
                <p>Olá, <strong>%s</strong>!</p>
                <p>Recebemos uma solicitação para redefinir a senha da sua conta.</p>
                <p>Clique no botão abaixo para criar uma nova senha:</p>
                <a href="%s"
                   style="display:inline-block; background:#FF6B00; color:#fff;
                          padding:12px 24px; border-radius:6px; text-decoration:none;
                          font-weight:bold; margin: 16px 0;">
                    Redefinir minha senha
                </a>
                <p style="color:#666; font-size:13px;">
                    Ou copie esse link:<br>
                    <a href="%s" style="color:#FF6B00;word-break:break-all">%s</a>
                </p>
                <p style="color:#666; font-size:13px;">
                    Este link expira em <strong>30 minutos</strong>.<br>
                    Se você não solicitou isso, ignore este e-mail.
                </p>
            </div>
        """.formatted(nome, link, link, link);
    }

    // ═════════════════════════════════════════════════════════════════════
    // BILLING — e-mails transacionais de assinatura/pagamento
    // Tolerantes a falha (log mas não propaga) + assíncronos.
    // ═════════════════════════════════════════════════════════════════════

    private static final DateTimeFormatter DATA_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Async
    public void pagamentoAprovado(String para, String nomeRestaurante, String plano,
                                  BigDecimal valor, LocalDateTime proximaCobranca) {
        enviarBilling(para, "MyDelivery — Pagamento aprovado",
                tpl("✅ Pagamento aprovado",
                    "Recebemos seu pagamento com sucesso. Seu plano está ativo!",
                    info("Restaurante", nomeRestaurante,
                         "Plano", plano,
                         "Valor pago", money(valor),
                         "Próxima cobrança", dt(proximaCobranca)),
                    "Acessar painel", "https://mydeliveryfood.com.br/painel.html"));
    }

    @Async
    public void pagamentoRecusado(String para, String nomeRestaurante, String plano,
                                  BigDecimal valor, String motivo) {
        enviarBilling(para, "MyDelivery — Pagamento recusado",
                tpl("⚠️ Pagamento recusado",
                    "Não conseguimos processar seu pagamento. Atualize seus dados pra evitar interrupção do serviço.",
                    info("Restaurante", nomeRestaurante,
                         "Plano", plano,
                         "Valor", money(valor),
                         "Motivo", motivo == null ? "Cartão recusado pela operadora" : motivo),
                    "Atualizar pagamento", "https://mydeliveryfood.com.br/planos.html"));
    }

    @Async
    public void venceEm(String para, String nomeRestaurante, int diasRestantes, LocalDateTime venceEm) {
        enviarBilling(para, "MyDelivery — Sua assinatura vence em breve",
                tpl("⏰ Sua assinatura vence em " + diasRestantes + " dias",
                    "Garanta que o pagamento será processado sem problemas. Confira seus dados.",
                    info("Restaurante", nomeRestaurante,
                         "Vence em", dt(venceEm),
                         "Dias restantes", String.valueOf(diasRestantes)),
                    "Gerenciar assinatura", "https://mydeliveryfood.com.br/planos.html"));
    }

    @Async
    public void canceladoPeloCliente(String para, String nomeRestaurante, LocalDateTime acessoAte) {
        enviarBilling(para, "MyDelivery — Plano cancelado",
                tpl("👋 Plano cancelado",
                    "Recebemos sua solicitação de cancelamento. Você continua com acesso completo "
                          + "até a data de fim do seu período já pago.",
                    info("Restaurante", nomeRestaurante,
                         "Acesso até", dt(acessoAte)),
                    "Reativar", "https://mydeliveryfood.com.br/planos.html"));
    }

    @Async
    public void canceladoPeloMyDelivery(String para, String nomeRestaurante, String motivo) {
        enviarBilling(para, "MyDelivery — Plano encerrado",
                tpl("❌ Plano cancelado pela equipe MyDelivery",
                    "Sua assinatura foi encerrada pela nossa equipe.",
                    info("Restaurante", nomeRestaurante,
                         "Motivo", motivo == null ? "Não informado" : motivo),
                    "Falar com suporte", "https://mydeliveryfood.com.br/suporte.html"));
    }

    @Async
    public void renovado(String para, String nomeRestaurante, String plano,
                         BigDecimal valor, LocalDateTime proximaCobranca) {
        pagamentoAprovado(para, nomeRestaurante, plano, valor, proximaCobranca);
    }

    @Async
    public void suspenso(String para, String nomeRestaurante, String motivo) {
        enviarBilling(para, "MyDelivery — Cadastro suspenso",
                tpl("🔒 Cadastro suspenso",
                    "Seu acesso ao painel está temporariamente suspenso. Regularize pra liberar.",
                    info("Restaurante", nomeRestaurante,
                         "Motivo", motivo == null ? "—" : motivo),
                    "Regularizar", "https://mydeliveryfood.com.br/planos.html"));
    }

    @Async
    public void reativado(String para, String nomeRestaurante) {
        enviarBilling(para, "MyDelivery — Cadastro reativado",
                tpl("✨ Cadastro reativado",
                    "Tudo certo! Seu acesso está liberado novamente.",
                    info("Restaurante", nomeRestaurante),
                    "Acessar painel", "https://mydeliveryfood.com.br/painel.html"));
    }

    @Async
    public void mesGratisConcedido(String para, String nomeRestaurante, LocalDateTime novoVencimento) {
        enviarBilling(para, "MyDelivery — 1 mês grátis na sua assinatura 🎁",
                tpl("🎁 Você ganhou 1 mês grátis!",
                    "Nossa equipe creditou 1 mês adicional na sua assinatura. Aproveite!",
                    info("Restaurante", nomeRestaurante,
                         "Nova data de cobrança", dt(novoVencimento)),
                    "Ver detalhes", "https://mydeliveryfood.com.br/planos.html"));
    }

    // ─── helpers privados ────────────────────────────────────────────────

    private void enviarBilling(String para, String assunto, String html) {
        if (para == null || !para.contains("@")) { log.debug("[Email] e-mail inválido: {}", para); return; }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
            h.setFrom(remetente, nomeRemetente);
            h.setTo(para);
            h.setSubject(assunto);
            h.setText(html, true);
            mailSender.send(msg);
            log.info("[Email] enviado pra {} — {}", para, assunto);
        } catch (Exception e) {
            log.warn("[Email] falha pra {}: {}", para, e.getMessage());
        }
    }

    private String tpl(String titulo, String intro, String infoHtml, String btnLabel, String btnHref) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>"
              + "<body style=\"margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#f5f5f0\">"
              + "<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"padding:30px 16px\"><tr><td align=\"center\">"
              + "<table cellpadding=\"0\" cellspacing=\"0\" width=\"560\" style=\"max-width:560px;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 4px 20px rgba(15,23,42,.08)\">"
              + "<tr><td style=\"background:#E55F00;padding:24px 30px;color:#fff\">"
              + "<div style=\"font-size:13px;font-weight:700;letter-spacing:.06em;opacity:.85\">MYDELIVERY</div>"
              + "<div style=\"font-size:22px;font-weight:800;margin-top:4px\">" + esc(titulo) + "</div>"
              + "</td></tr>"
              + "<tr><td style=\"padding:28px 30px;color:#1a1a1a;line-height:1.55;font-size:14.5px\">"
              + "<p style=\"margin:0 0 18px\">" + esc(intro) + "</p>"
              + "<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"background:#fafaf8;border-radius:10px;padding:14px 18px;margin-bottom:22px\">"
              + infoHtml + "</table>"
              + (btnLabel != null && btnHref != null
                ? "<div style=\"text-align:center\"><a href=\"" + esc(btnHref) + "\" style=\"display:inline-block;background:#E55F00;color:#fff;text-decoration:none;font-weight:700;padding:13px 28px;border-radius:9px;font-size:14px\">"
                  + esc(btnLabel) + "</a></div>"
                : "")
              + "</td></tr>"
              + "<tr><td style=\"padding:18px 30px;background:#fafaf8;color:#888;font-size:12px;text-align:center;border-top:1px solid #ecebe8\">"
              + "Este é um e-mail automático do MyDelivery. Dúvidas? Responda este e-mail ou fale com o suporte."
              + "</td></tr></table></td></tr></table></body></html>";
    }

    private String info(String... pares) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < pares.length; i += 2) {
            String c = pares[i], v = pares[i + 1];
            if (v == null || v.isBlank()) continue;
            sb.append("<tr><td style=\"padding:5px 0;font-size:13px;color:#666;width:42%\">")
              .append(esc(c))
              .append("</td><td style=\"padding:5px 0;font-size:13px;color:#1a1a1a;font-weight:700;text-align:right\">")
              .append(esc(v)).append("</td></tr>");
        }
        return sb.toString();
    }

    private static String money(BigDecimal v) {
        if (v == null) return "R$ 0,00";
        return "R$ " + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    private static String dt(LocalDateTime d) {
        return d == null ? "—" : d.format(DATA_BR);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}