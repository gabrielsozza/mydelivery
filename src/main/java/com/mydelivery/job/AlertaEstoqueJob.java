package com.mydelivery.job;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.model.Insumo;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.InsumoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Roda diariamente às 9h. Pra cada restaurante que tem insumos abaixo do mínimo,
 * envia 1 email consolidado pro dono com a lista.
 *
 * NÃO faz "marcação de já notificado" porque o usuário pediu o resumo simples —
 * roda 1× por dia e pronto. Se quiser parar de receber: ajustar o saldoMinimo
 * dos insumos pra zero (não dispara mais).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertaEstoqueJob {

    private final InsumoRepository insumoRepository;
    private final RestauranteRepository restauranteRepository;
    private final JavaMailSender mailSender;

    @Scheduled(cron = "0 0 9 * * *")
    public void verificarEEnviarAlertas() {
        log.info("📦 Job: verificando estoques abaixo do mínimo...");

        // Agrupa todos os insumos baixos por restaurante (1 query)
        List<Insumo> baixos = insumoRepository.findAll().stream()
                .filter(i -> Boolean.TRUE.equals(i.getAtivo()))
                .filter(i -> i.getSaldoAtual() != null && i.getSaldoMinimo() != null)
                .filter(i -> i.getSaldoAtual().compareTo(i.getSaldoMinimo()) <= 0)
                .toList();

        if (baixos.isEmpty()) {
            log.info("✅ Nenhum insumo abaixo do mínimo hoje.");
            return;
        }

        Map<Long, List<Insumo>> porRestaurante = baixos.stream()
                .filter(i -> i.getRestaurante() != null)
                .collect(Collectors.groupingBy(i -> i.getRestaurante().getId()));

        int totalEnviados = 0;
        for (Map.Entry<Long, List<Insumo>> e : porRestaurante.entrySet()) {
            Restaurante r = restauranteRepository.findById(e.getKey()).orElse(null);
            if (r == null || r.getUsuario() == null || r.getUsuario().getEmail() == null) continue;
            try {
                enviarEmail(r, e.getValue());
                totalEnviados++;
            } catch (Exception ex) {
                log.error("❌ Erro ao enviar alerta pro restaurante {}: {}", r.getNome(), ex.getMessage());
            }
        }
        log.info("📨 Alertas de estoque enviados: {}", totalEnviados);
    }

    private void enviarEmail(Restaurante r, List<Insumo> insumosBaixos) {
        StringBuilder body = new StringBuilder();
        body.append("Olá, ").append(r.getNome()).append("!\n\n");
        body.append("Esses insumos estão abaixo do estoque mínimo no MyDelivery:\n\n");

        for (Insumo i : insumosBaixos) {
            String un = i.getUnidade() != null ? i.getUnidade().name().toLowerCase() : "un";
            BigDecimal saldo = i.getSaldoAtual() != null ? i.getSaldoAtual() : BigDecimal.ZERO;
            BigDecimal min = i.getSaldoMinimo() != null ? i.getSaldoMinimo() : BigDecimal.ZERO;
            String status = saldo.signum() <= 0 ? "[ZERADO]" : "[BAIXO]";
            body.append("• ").append(status).append(" ").append(i.getNome())
                .append(" — saldo atual: ").append(saldo).append(" ").append(un)
                .append(" / mínimo: ").append(min).append(" ").append(un).append("\n");
        }
        body.append("\nAcesse o painel pra registrar uma nova compra:\n")
            .append("http://localhost:5500/estoque.html\n\n")
            .append("— Equipe MyDelivery");

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(r.getUsuario().getEmail());
        msg.setSubject("⚠️ " + insumosBaixos.size() + " insumo(s) precisam de reposição");
        msg.setText(body.toString());
        mailSender.send(msg);

        log.info("✉️ Email de alerta enviado pra {} ({} insumos baixos)",
                r.getUsuario().getEmail(), insumosBaixos.size());
    }
}
