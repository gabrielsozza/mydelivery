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
        int abriu = 0, fechou = 0, erros = 0;
        for (Restaurante r : todos) {
            try {
                if (processar(r)) {
                    restauranteRepo.save(r);
                    if (Boolean.TRUE.equals(r.getAberto())) abriu++; else fechou++;
                }
            } catch (Exception e) {
                erros++;
                // WARN (não debug) — sem isso erros viravam invisíveis e
                // dono pensava que job nem rodava (causa real do bug).
                log.warn("[HorarioLojaJob] restaurante={} erro={}", r.getId(), e.getMessage());
            }
        }
        if (abriu > 0 || fechou > 0 || erros > 0) {
            log.info("[HorarioLojaJob] tick — total={} abriu={} fechou={} erros={}",
                    todos.size(), abriu, fechou, erros);
        }
    }

    /**
     * Retorna true se mudou o status do restaurante (precisa save).
     *
     * Regra de FECHAMENTO (sempre ativa):
     *  - Loja aberta no DB +
     *  - (não tem horário configurado pra hoje  OU  está fora do horário de hoje)
     *  → fecha automaticamente.
     *
     * O "não tem horário pra hoje" cobre o caso em que o dono abriu manualmente
     * num dia marcado como fechado (ex: domingo aberto:false mas ele clicou
     * "abrir agora") — depois de algumas horas, ainda precisamos fechar.
     * Antes, esse caso falhava porque horarioConfigurado=false bloqueava.
     */
    private boolean processar(Restaurante r) {
        EstadoHorario e = horarioService.calcular(r);
        boolean estaAberto = Boolean.TRUE.equals(r.getAberto());

        if (estaAberto) {
            if (e.horarioConfigurado && !e.dentroHorario) {
                // Caso normal: passou do horário de fechamento.
                log.info("[HorarioLojaJob] fechando restaurante={} (fora do horário {} → {})",
                        r.getId(), e.abertura, e.fechamento);
                r.setAberto(false);
                return true;
            }
            // NOTA: se não tem horário pra hoje (e.horarioConfigurado=false),
            // mantemos respeito ao manual — não fechamos automaticamente.
            // Isso evita fechar lojas que ainda não cadastraram horário.
        } else {
            // ABERTURA automática (opt-in): só se o toggle está ativo E está
            // dentro do horário. Não abre se já está aberta (idempotente).
            if (Boolean.TRUE.equals(r.getAberturaAutomatica())
                    && e.horarioConfigurado && e.dentroHorario) {
                log.info("[HorarioLojaJob] abrindo restaurante={} (dentro do horário {} → {})",
                        r.getId(), e.abertura, e.fechamento);
                r.setAberto(true);
                return true;
            }
        }
        return false;
    }
}
