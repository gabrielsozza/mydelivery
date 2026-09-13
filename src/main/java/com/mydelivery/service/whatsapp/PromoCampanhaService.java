package com.mydelivery.service.whatsapp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.Cliente;
import com.mydelivery.model.PromoCampanha;
import com.mydelivery.model.PromoCampanhaEnvio;
import com.mydelivery.model.PromoOptOut;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.ClienteRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.PromoCampanhaEnvioRepository;
import com.mydelivery.repository.PromoCampanhaRepository;
import com.mydelivery.repository.PromoOptOutRepository;
import com.mydelivery.repository.WhatsappInstanceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * Regras de negócio das campanhas de disparo do WhatsApp.
 *
 * <p>Filosofia: cada método muda estado e devolve o estado atualizado — sem
 * lógica de "fire and forget" aqui dentro. O envio propriamente dito roda
 * no {@link PromoCampanhaSender} (job agendado), que respeita os limites
 * anti-ban configurados no {@code enviarMensagem} do {@link WhatsappService}
 * (rate por hora + rate por número + dedup) e adiciona um cap adicional
 * de broadcast por hora/dia.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromoCampanhaService {

    private final PromoCampanhaRepository campanhaRepo;
    private final PromoCampanhaEnvioRepository envioRepo;
    private final PromoOptOutRepository optOutRepo;
    private final PedidoRepository pedidoRepo;
    private final ClienteRepository clienteRepo;
    private final WhatsappInstanceRepository instanceRepo;

    /** Estima quantos clientes ELEGÍVEIS existem HOJE — sem criar campanha ainda.
     *  Front usa pra mostrar "vai chegar em ~X clientes" antes do dono confirmar.
     *
     *  Fonte: aba Clientes (que unifica cadastros manuais, importação CSV e
     *  clientes que já compraram). Filtro anti-ruído: só telefones válidos
     *  BR (10-11 dígitos) e sem opt-out.
     *
     *  minCompras &lt;= 1 envia pra TODOS os cadastros. minCompras &gt;= 2 filtra
     *  só quem já comprou N+ vezes (usa contagem de pedidos por telefone). */
    @Transactional(readOnly = true)
    public int estimarDestinos(Restaurante restaurante, int minCompras) {
        return coletarTelefonesElegiveis(restaurante, minCompras).size();
    }

    /**
     * Devolve mapa telefone → nome cliente, filtrado por opt-out, minCompras
     * e telefones válidos. Fonte única: tabela Cliente. Quando minCompras &gt;= 2,
     * cruza com histórico de pedidos pra manter só quem tem N+ compras.
     */
    private Map<String, String> coletarTelefonesElegiveis(Restaurante restaurante, int minCompras) {
        Set<String> optOuts = new HashSet<>();
        for (PromoOptOut o : optOutRepo.findByRestauranteId(restaurante.getId())) {
            optOuts.add(o.getTelefone());
        }

        // Fonte primária: aba Clientes (cadastro manual + importação + WhatsApp bot)
        Map<String, String> baseClientes = new LinkedHashMap<>();
        for (Cliente c : clienteRepo.findByRestauranteIdOrderByNomeAsc(restaurante.getId())) {
            String tel = normalizarTel(c.getTelefone());
            if (tel == null) continue;
            if (optOuts.contains(tel)) continue;
            baseClientes.putIfAbsent(tel, c.getNome());
        }

        // Se dono pediu filtro por "clientes recorrentes" (min &gt;= 2), cruza com pedidos
        if (minCompras >= 2) {
            Set<String> recorrentes = new HashSet<>();
            for (Object[] row : pedidoRepo.findClientesRecorrentes(restaurante.getId(), minCompras)) {
                String tel = normalizarTel((String) row[0]);
                if (tel != null) recorrentes.add(tel);
            }
            baseClientes.keySet().retainAll(recorrentes);
        }

        return baseClientes;
    }

    /** Aceita "84999998888", "(84) 99999-8888", "5584999998888". Sempre volta
     *  o formato canônico (só dígitos, sem DDI 55). Rejeita &lt;10 ou &gt;13 dígitos. */
    private String normalizarTel(String bruto) {
        if (bruto == null) return null;
        String d = bruto.replaceAll("\\D", "");
        if (d.isEmpty()) return null;
        if (d.length() == 13 && d.startsWith("55")) d = d.substring(2);
        if (d.length() == 12 && d.startsWith("55")) d = d.substring(2);
        if (d.length() < 10 || d.length() > 11) return null;
        return d;
    }

    /**
     * Cria uma nova campanha e já dispara — popula a fila de destinos e vai
     * pra ENVIANDO. Sender pega no próximo tick.
     *
     * @throws ResponseStatusException 409 se restaurante tem outra campanha ativa
     * @throws ResponseStatusException 400 se texto vazio ou WhatsApp não conectado
     */
    @Transactional
    public PromoCampanha criarEDisparar(Restaurante restaurante, String texto, int minCompras) {
        String textoLimpo = texto == null ? "" : texto.trim();
        if (textoLimpo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Texto da promoção é obrigatório.");
        }
        if (textoLimpo.length() > 700) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Texto muito longo (máx. 700 caracteres — deixa espaço pra rodapé de opt-out).");
        }

        WhatsappInstance inst = instanceRepo.findByRestauranteId(restaurante.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "WhatsApp ainda não conectado — abra a aba Assistente e escaneie o QR primeiro."));
        if (inst.getStatus() != WhatsappInstance.Status.CONECTADA) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "WhatsApp desconectado — reconecte antes de disparar campanhas.");
        }

        Optional<PromoCampanha> ativa = campanhaRepo.findAtivaDoRestaurante(restaurante.getId());
        if (ativa.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Você já tem uma campanha em andamento. Aguarde terminar (ou cancele) antes de criar outra.");
        }

        // ── Monta lista de destinos elegíveis ─────────────────────────────
        // Fonte: tabela Cliente (aba Clientes). minCompras < 2 = envia pra TODOS
        // os cadastros; minCompras >= 2 = só clientes que já compraram N+ vezes.
        int minCompras2 = Math.max(1, minCompras);
        Map<String, String> nomePorTelefone = coletarTelefonesElegiveis(restaurante, minCompras2);
        if (nomePorTelefone.isEmpty()) {
            String msg = minCompras2 <= 1
                    ? "Nenhum cliente com telefone cadastrado. Cadastre ou importe clientes na aba Clientes antes de disparar campanha."
                    : "Nenhum cliente com " + minCompras2 + "+ compras encontrado. Reduza o filtro ou envie pra todos.";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }

        PromoCampanha campanha = PromoCampanha.builder()
                .restaurante(restaurante)
                .texto(textoLimpo)
                .minCompras(minCompras2)
                .status(PromoCampanha.Status.ENVIANDO)
                .criadaEm(LocalDateTime.now())
                .iniciadaEm(LocalDateTime.now())
                .totalDestinos(nomePorTelefone.size())
                .totalEnviados(0)
                .totalFalhas(0)
                .build();
        campanha = campanhaRepo.save(campanha);

        List<PromoCampanhaEnvio> envios = new ArrayList<>(nomePorTelefone.size());
        for (Map.Entry<String, String> e : nomePorTelefone.entrySet()) {
            envios.add(PromoCampanhaEnvio.builder()
                    .campanha(campanha)
                    .telefone(e.getKey())
                    .nomeCliente(e.getValue())
                    .build());
        }
        envioRepo.saveAll(envios);
        log.info("[PromoCampanha] Criada id={} restaurante={} destinos={}",
                campanha.getId(), restaurante.getId(), envios.size());
        return campanha;
    }

    @Transactional
    public PromoCampanha pausar(Restaurante restaurante, Long campanhaId) {
        PromoCampanha c = exigirDoRestaurante(restaurante, campanhaId);
        if (c.getStatus() != PromoCampanha.Status.ENVIANDO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Só campanhas em andamento podem ser pausadas.");
        }
        c.setStatus(PromoCampanha.Status.PAUSADA);
        return campanhaRepo.save(c);
    }

    @Transactional
    public PromoCampanha retomar(Restaurante restaurante, Long campanhaId) {
        PromoCampanha c = exigirDoRestaurante(restaurante, campanhaId);
        if (c.getStatus() != PromoCampanha.Status.PAUSADA) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Só campanhas pausadas podem ser retomadas.");
        }
        c.setStatus(PromoCampanha.Status.ENVIANDO);
        return campanhaRepo.save(c);
    }

    @Transactional
    public PromoCampanha cancelar(Restaurante restaurante, Long campanhaId) {
        PromoCampanha c = exigirDoRestaurante(restaurante, campanhaId);
        if (c.getStatus() == PromoCampanha.Status.CONCLUIDA
                || c.getStatus() == PromoCampanha.Status.CANCELADA) {
            return c;
        }
        c.setStatus(PromoCampanha.Status.CANCELADA);
        c.setConcluidaEm(LocalDateTime.now());
        return campanhaRepo.save(c);
    }

    @Transactional(readOnly = true)
    public List<PromoCampanha> listar(Restaurante restaurante) {
        return campanhaRepo.findByRestauranteIdOrderByCriadaEmDesc(restaurante.getId());
    }

    @Transactional(readOnly = true)
    public PromoCampanha detalhe(Restaurante restaurante, Long campanhaId) {
        return exigirDoRestaurante(restaurante, campanhaId);
    }

    private PromoCampanha exigirDoRestaurante(Restaurante restaurante, Long campanhaId) {
        PromoCampanha c = campanhaRepo.findById(campanhaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Campanha não encontrada."));
        if (!c.getRestaurante().getId().equals(restaurante.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Campanha pertence a outro restaurante.");
        }
        return c;
    }
}
