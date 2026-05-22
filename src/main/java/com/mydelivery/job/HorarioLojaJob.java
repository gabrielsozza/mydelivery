package com.mydelivery.job;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.HorarioLojaService;
import com.mydelivery.service.HorarioLojaService.EstadoHorario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Job que abre/fecha a loja automaticamente baseado no horário cadastrado.
 *
 * Regras:
 *  - FECHAMENTO automático é PADRÃO: passou do horário de fechamento → loja
 *    vira aberto=false, mesmo sem ninguém logado.
 *  - ABERTURA automática só rola se Restaurante.aberturaAutomatica = true.
 *    Caso contrário, o dono precisa abrir manualmente todo dia.
 *
 * Roda a cada 60s — granularidade suficiente pra UX (cliente que entra 1min
 * depois da abertura ainda vê a loja aberta).
 *
 * Tolerante a erro: se um restaurante quebrar, segue pros outros sem propagar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HorarioLojaJob {

    private final RestauranteRepository restauranteRepo;
    private final HorarioLojaService horarioService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void atualizarStatusAutomatico() {
        List<Restaurante> todos;
        try {
            todos = restauranteRepo.findAll();
        } catch (Exception e) {
            log.warn("[HorarioLojaJob] falha ao listar restaurantes: {}", e.getMessage());
            return;
        }
        int abriu = 0, fechou = 0;
        for (Restaurante r : todos) {
            try {
                if (processar(r)) {
                    restauranteRepo.save(r);
                    if (Boolean.TRUE.equals(r.getAberto())) abriu++; else fechou++;
                }
            } catch (Exception e) {
                log.debug("[HorarioLojaJob] restaurante={} erro={}", r.getId(), e.getMessage());
            }
        }
        if (abriu > 0 || fechou > 0) {
            log.info("[HorarioLojaJob] abriu={} fechou={}", abriu, fechou);
        }
    }

    /** Retorna true se mudou o status do restaurante (precisa save). */
    private boolean processar(Restaurante r) {
        EstadoHorario e = horarioService.calcular(r);
        boolean estaAberto = Boolean.TRUE.equals(r.getAberto());

        // FECHAMENTO automático (padrão pra todos): se passou do horário,
        // loja deveria estar fechada. Só fecha se está aberta agora.
        if (estaAberto && e.horarioConfigurado && !e.dentroHorario) {
            r.setAberto(false);
            return true;
        }
        // ABERTURA automática (opt-in): só se o toggle está ativo E está
        // dentro do horário. Não abre se já está aberta (idempotente).
        if (!estaAberto && Boolean.TRUE.equals(r.getAberturaAutomatica())
                && e.horarioConfigurado && e.dentroHorario) {
            r.setAberto(true);
            return true;
        }
        return false;
    }
}
