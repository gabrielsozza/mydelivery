package com.mydelivery.service.myhelp;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mydelivery.model.MyHelpMemoria;
import com.mydelivery.repository.MyHelpMemoriaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gerência da memória do myHelp (camada de aprendizado persistente por loja).
 *
 * <p><b>Como funciona o aprendizado (sem retreinar modelo):</b> quando o dono
 * ensina/corrige ("quando eu falar peixe é Peixe Frito"), gravamos um ALIAS
 * daquela loja com confiança baixa. Repetir a mesma coisa SOBE a confiança;
 * contradizer REVISA (substitui o valor e reseta a confiança). A memória é lida
 * como candidato auxiliar na hora de casar o produto/bairro — nunca troca o
 * texto do dono cegamente.
 *
 * <p><b>Controle de crescimento:</b> dedup por (loja+contexto+chave) via upsert;
 * nada é duplicado. Memória temporária de conversa NÃO passa por aqui (fica no
 * cache Caffeine curto do MyHelpService). Leitura é cacheada 30s por (loja,ctx)
 * pra não bater no banco a cada mensagem.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyHelpMemoriaService {

    private final MyHelpMemoriaRepository repo;

    /** Cache curto de leitura: chave "loja::contexto" → memórias ativas. */
    private final Cache<String, List<MyHelpMemoria>> cacheLeitura =
            Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.SECONDS).maximumSize(5000).build();

    private static String chaveCache(Long loja, String ctx) { return loja + "::" + ctx; }

    /** Memórias ativas do contexto (da loja + globais), mais confiáveis primeiro. */
    public List<MyHelpMemoria> ativas(Long lojaId, String contexto) {
        if (lojaId == null || contexto == null) return List.of();
        return cacheLeitura.get(chaveCache(lojaId, contexto), k -> repo.ativasDoContexto(lojaId, contexto));
    }

    /**
     * Ensina/atualiza um ALIAS da loja (upsert com confiança). Chamado quando o
     * dono corrige ou ensina explicitamente. Não cria duplicata.
     */
    @Transactional
    public MyHelpMemoria aprender(Long lojaId, String contexto, String gatilho, String valor) {
        if (lojaId == null || gatilho == null || valor == null) return null;
        String chave = MyHelpTexto.norm(gatilho);
        String val = valor.trim();
        if (chave.isBlank() || val.isBlank()) return null;

        Optional<MyHelpMemoria> exist = repo.findByRestauranteIdAndContextoAndChaveNorm(lojaId, contexto, chave);
        MyHelpMemoria m;
        if (exist.isEmpty()) {
            m = MyHelpMemoria.builder()
                    .restauranteId(lojaId).tipo(MyHelpMemoria.Tipo.ALIAS)
                    .contexto(contexto).chaveNorm(chave).valor(val)
                    .confianca(0.6).usos(1).ativa(true).build();
        } else {
            m = exist.get();
            if (m.getValor() != null && m.getValor().equalsIgnoreCase(val)) {
                // Mesma coisa de novo → reforça.
                m.setConfianca(Math.min(0.98, (m.getConfianca() == null ? 0.6 : m.getConfianca()) + 0.15));
                m.setUsos((m.getUsos() == null ? 1 : m.getUsos()) + 1);
            } else {
                // Contradição / mudança de ideia → REVISA (adota o novo, reseta confiança).
                log.info("[myHelp-mem] loja={} contexto={} chave='{}' revisada '{}' -> '{}'",
                        lojaId, contexto, chave, m.getValor(), val);
                m.setValor(val);
                m.setConfianca(0.6);
                m.setUsos(1);
            }
            m.setAtiva(true);
        }
        m = repo.save(m);
        cacheLeitura.invalidate(chaveCache(lojaId, contexto));
        log.info("[myHelp-mem] loja={} aprendeu '{}' -> '{}' (conf={})", lojaId, chave, val, m.getConfianca());
        return m;
    }

    /** Reforço leve: a memória ajudou a resolver algo → sobe usos/confiança um pouco. */
    @Transactional
    public void reforcar(Long memoriaId) {
        if (memoriaId == null) return;
        repo.findById(memoriaId).ifPresent(m -> {
            m.setUsos((m.getUsos() == null ? 1 : m.getUsos()) + 1);
            m.setConfianca(Math.min(0.98, (m.getConfianca() == null ? 0.6 : m.getConfianca()) + 0.03));
            repo.save(m);
            cacheLeitura.invalidate(chaveCache(m.getRestauranteId(), m.getContexto()));
        });
    }

    /** Lista tudo de uma loja — pra tela futura "Aprendizados do myHelp". */
    public List<MyHelpMemoria> daLoja(Long lojaId) {
        return lojaId == null ? List.of() : repo.findByRestauranteIdOrderByAtualizadoEmDesc(lojaId);
    }
}
