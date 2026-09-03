package com.mydelivery.fiscal.job;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.fiscal.repository.PerfilFiscalRestauranteRepository;
import com.mydelivery.fiscal.service.NfceEmissorService;
import com.mydelivery.fiscal.service.NfceStorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fechamento mensal automático — todo dia 1º às 06:05 gera o ZIP consolidado
 * do MÊS ANTERIOR pra cada loja com emissão ativa. Grava no storage
 * ({@code _relatorios/{cnpj}/YYYY-MM.zip}) pra o dono baixar já pronto na
 * tela Relatório NFC-e (contador recebe ZIP com XMLs de saída + XMLs de
 * entrada + resumos CSV + LEIA-ME).
 *
 * <p>Idempotente: rodar 2x sobrescreve o mesmo ZIP. Se der erro numa loja,
 * segue pras próximas (não trava a fila).
 *
 * <p>Ativação por {@code mydelivery.fiscal.retry.ativo=true} (mesma flag do
 * NfceRetryJob — quem já rodou retry, também roda fechamento).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FechamentoMensalJob {

    private final PerfilFiscalRestauranteRepository perfilRepo;
    private final NfceEmissorService emissor;
    private final NfceStorageService storage;

    @Value("${mydelivery.fiscal.retry.ativo:false}")
    private boolean ativo;

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Cron do Spring: {@code segundo minuto hora diaMês mês diaSemana}
     * → dia 1 do mês, 06:05:00 (fuso do servidor Railway = UTC, então 03:05 BRT).
     * Bem antes do horário comercial pra não sobrecarregar.
     */
    @Scheduled(cron = "0 5 6 1 * *")
    public void gerarFechamento() {
        if (!ativo) {
            log.debug("[Fiscal][Fecha] Desativado (FISCAL_RETRY_ATIVO=false). Skip.");
            return;
        }

        YearMonth mesPassado = YearMonth.now().minusMonths(1);
        LocalDate ini = mesPassado.atDay(1);
        LocalDate fim = mesPassado.atEndOfMonth();
        String ym = mesPassado.format(YM);

        log.info("[Fiscal][Fecha] Iniciando fechamento de {} ({} a {})", ym, ini, fim);

        int ok = 0, err = 0;
        var perfis = perfilRepo.findAll();
        for (var p : perfis) {
            if (!Boolean.TRUE.equals(p.getEmissaoAtiva())) continue;
            if (p.getCnpj() == null || p.getCnpj().isBlank()) continue;
            try {
                byte[] zip = emissor.montarRelatorioZip(p.getRestaurante().getId(),
                        ini.toString(), fim.toString());
                if (zip == null || zip.length < 100) {
                    log.warn("[Fiscal][Fecha] ZIP vazio cnpj={} — skip", p.getCnpj());
                    continue;
                }
                String url = storage.gravarRelatorio(p.getCnpj(), ym, zip);
                log.info("[Fiscal][Fecha] {} cnpj={} → {} ({} bytes)", ym, p.getCnpj(), url, zip.length);
                ok++;
            } catch (Exception e) {
                err++;
                log.error("[Fiscal][Fecha] Falhou cnpj={}: {}", p.getCnpj(), e.toString());
            }
        }
        log.info("[Fiscal][Fecha] Concluído. ok={} err={} mes={}", ok, err, ym);
    }
}
