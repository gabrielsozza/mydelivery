package com.mydelivery.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

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

    public void enviarRecuperacaoSenha(String destinatario, String nome, String token) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(remetente, nomeRemetente);
            helper.setTo(destinatario);
            helper.setSubject("myDelivery — Recuperação de senha");

            String link = appUrl + "/redefinir-senha.html?token=" + token;

            String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #e63946;">myDelivery</h2>
                    <p>Olá, <strong>%s</strong>!</p>
                    <p>Recebemos uma solicitação para redefinir a senha da sua conta.</p>
                    <p>Clique no botão abaixo para criar uma nova senha:</p>
                    <a href="%s"
                       style="display:inline-block; background:#e63946; color:#fff;
                              padding:12px 24px; border-radius:6px; text-decoration:none;
                              font-weight:bold; margin: 16px 0;">
                        Redefinir minha senha
                    </a>
                    <p style="color:#666; font-size:13px;">
                        Este link expira em <strong>30 minutos</strong>.<br>
                        Se você não solicitou isso, ignore este e-mail.
                    </p>
                </div>
            """.formatted(nome, link);

            helper.setText(html, true);
            mailSender.send(message);

        } catch (Exception e) {
            throw new RuntimeException("Erro ao enviar e-mail: " + e.getMessage());
        }
    }
}