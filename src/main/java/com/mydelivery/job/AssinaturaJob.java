package com.mydelivery.job;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.Assinatura;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.AssinaturaRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssinaturaJob {

    private final RestauranteRepository restauranteRepository;
    private final AssinaturaRepository assinaturaRepository;

    // Roda todo dia à meia-noite
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void verificarTrialsExpirados() {
        log.info("⏰ Job: verificando trials expirados...");

        List<Restaurante> expirados = restauranteRepository.findAll().stream()
                .filter(r -> r.getStatus() == Restaurante.Status.TRIAL
                        && r.getTrialExpiraEm() != null
                        && r.getTrialExpiraEm().isBefore(LocalDateTime.now()))
                .toList();

        for (Restaurante restaurante : expirados) {
            restaurante.setStatus(Restaurante.Status.BLOQUEADO);
            restaurante.setBloqueadoEm(LocalDateTime.now());
            restaurante.setMotivoBloqueio("Trial expirado. Assine um plano para continuar.");
            restauranteRepository.save(restaurante);

            assinaturaRepository.findByRestauranteId(restaurante.getId())
                    .ifPresent(a -> {
                        a.setStatus(Assinatura.Status.INADIMPLENTE);
                        assinaturaRepository.save(a);
                    });

            log.warn("🔒 Restaurante bloqueado: {} (trial expirado em {})",
                    restaurante.getNome(), restaurante.getTrialExpiraEm());
        }

        log.info("✅ Job finalizado. {} restaurantes bloqueados.", expirados.size());
    }

    // Roda todo dia às 8h — avisa restaurantes com trial expirando em 3 dias
    @Scheduled(cron = "0 0 8 * * *")
    @Transactional
    public void verificarTrialsProximosExpirar() {
        log.info("⏰ Job: verificando trials próximos de expirar...");

        LocalDateTime em3Dias = LocalDateTime.now().plusDays(3);

        List<Restaurante> proximosExpirar = restauranteRepository.findAll().stream()
                .filter(r -> r.getStatus() == Restaurante.Status.TRIAL
                        && r.getTrialExpiraEm() != null
                        && r.getTrialExpiraEm().isBefore(em3Dias)
                        && r.getTrialExpiraEm().isAfter(LocalDateTime.now()))
                .toList();

        for (Restaurante restaurante : proximosExpirar) {
            log.info("⚠️  Trial expira em breve: {} — expira em {}",
                    restaurante.getNome(), restaurante.getTrialExpiraEm());
            // Aqui futuramente você pode disparar um e-mail de aviso
        }
    }
}