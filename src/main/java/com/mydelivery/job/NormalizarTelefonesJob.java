package com.mydelivery.job;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.repository.CarrinhoAbandonadoRepository;
import com.mydelivery.repository.ClienteRepository;
import com.mydelivery.repository.CupomRepository;
import com.mydelivery.repository.CupomUsoRepository;
import com.mydelivery.repository.PontosTransacaoRepository;
import com.mydelivery.util.TelefoneUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Roda uma vez no startup: normaliza todos os telefones existentes no banco
 * pra ficar consistente com a nova convenção (apenas dígitos).
 *
 * É IDEMPOTENTE — se já estiver normalizado, não faz nada. Pode rodar várias
 * vezes sem efeito colateral.
 *
 * Pode ser removido depois de rodar com sucesso uma vez em produção, mas
 * deixar não custa nada (overhead é mínimo nas próximas execuções).
 */
@Slf4j
@Component
public class NormalizarTelefonesJob implements CommandLineRunner {

    @Autowired private ClienteRepository clienteRepository;
    @Autowired private PontosTransacaoRepository pontosRepository;
    @Autowired private CarrinhoAbandonadoRepository carrinhoRepository;
    @Autowired private CupomRepository cupomRepository;
    @Autowired private CupomUsoRepository cupomUsoRepository;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("🔧 Verificando normalização de telefones no banco...");

        int clientes = normalizarClientes();
        int pontos = normalizarPontos();
        int carrinhos = normalizarCarrinhos();
        int cupons = normalizarCupons();
        int usos = normalizarCupomUsos();

        int total = clientes + pontos + carrinhos + cupons + usos;
        if (total == 0) {
            log.info("✅ Todos os telefones já estão normalizados.");
        } else {
            log.info("✅ Normalizados: {} clientes, {} pontos, {} carrinhos, {} cupons, {} usos = {} registros",
                    clientes, pontos, carrinhos, cupons, usos, total);
        }
    }

    private int normalizarClientes() {
        int count = 0;
        var lista = clienteRepository.findAll();
        for (var c : lista) {
            String orig = c.getTelefone();
            String norm = TelefoneUtil.normalizar(orig);
            if (norm != null && !norm.equals(orig)) {
                c.setTelefone(norm);
                clienteRepository.save(c);
                count++;
            }
        }
        return count;
    }

    private int normalizarPontos() {
        int count = 0;
        var lista = pontosRepository.findAll();
        for (var p : lista) {
            String orig = p.getTelefoneCliente();
            String norm = TelefoneUtil.normalizar(orig);
            if (norm != null && !norm.equals(orig)) {
                p.setTelefoneCliente(norm);
                pontosRepository.save(p);
                count++;
            }
        }
        return count;
    }

    private int normalizarCarrinhos() {
        int count = 0;
        var lista = carrinhoRepository.findAll();
        for (var c : lista) {
            String orig = c.getTelefoneCliente();
            String norm = TelefoneUtil.normalizar(orig);
            if (norm != null && !norm.equals(orig)) {
                c.setTelefoneCliente(norm);
                carrinhoRepository.save(c);
                count++;
            }
        }
        return count;
    }

    private int normalizarCupons() {
        int count = 0;
        var lista = cupomRepository.findAll();
        for (var c : lista) {
            String orig = c.getTelefoneCliente();
            String norm = TelefoneUtil.normalizar(orig);
            if (norm != null && !norm.equals(orig)) {
                c.setTelefoneCliente(norm);
                cupomRepository.save(c);
                count++;
            }
        }
        return count;
    }

    private int normalizarCupomUsos() {
        int count = 0;
        var lista = cupomUsoRepository.findAll();
        for (var u : lista) {
            String orig = u.getTelefoneCliente();
            String norm = TelefoneUtil.normalizar(orig);
            if (norm != null && !norm.equals(orig)) {
                u.setTelefoneCliente(norm);
                cupomUsoRepository.save(u);
                count++;
            }
        }
        return count;
    }
}
