package com.mydelivery.fiscal.service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;

import com.mydelivery.fiscal.model.NotaFiscalEntrada;
import com.mydelivery.fiscal.repository.NotaFiscalEntradaRepository;
import com.mydelivery.model.Restaurante;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Processa upload manual de NF-e RECEBIDA (fornecedor). Parseia campos
 * essenciais do XML (chave 44, emitente, valor, data) e grava o XML
 * completo pra o contador reimportar no fechamento mensal.
 *
 * <p>Duplicidade: chave de acesso é UNIQUE — upload da mesma NF-e 2x
 * retorna a existente sem duplicar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NfeEntradaService {

    private final NotaFiscalEntradaRepository repo;

    @Transactional
    public NotaFiscalEntrada salvarUpload(Restaurante r, byte[] xmlBytes, String usuarioEmail) {
        if (xmlBytes == null || xmlBytes.length < 50) {
            throw new IllegalArgumentException("Arquivo XML vazio ou inválido");
        }
        String xml = new String(xmlBytes, StandardCharsets.UTF_8);
        Metadados m = extrair(xml);

        if (m.chave == null || m.chave.length() != 44) {
            throw new IllegalArgumentException("XML não é NF-e válida (chave de acesso ausente ou inválida).");
        }

        // Idempotente: mesma chave = mesma nota
        var existente = repo.findByChaveAcesso(m.chave).orElse(null);
        if (existente != null) {
            log.info("[Fiscal][Entrada] chave={} ja cadastrada rest={} — retorno existente", m.chave, r.getId());
            return existente;
        }

        var n = NotaFiscalEntrada.builder()
                .restaurante(r)
                .cnpjEmitente(m.cnpjEmitente == null ? "" : m.cnpjEmitente)
                .nomeEmitente(m.nomeEmitente)
                .chaveAcesso(m.chave)
                .numero(m.numero)
                .modelo(m.modelo)
                .dataEmissao(m.dataEmissao)
                .valorTotal(m.valorTotal == null ? BigDecimal.ZERO : m.valorTotal)
                .xmlConteudo(xml)
                .usuarioUpload(usuarioEmail)
                .build();
        n = repo.save(n);
        log.info("[Fiscal][Entrada] rest={} salvou NF-e entrada chave={} fornecedor={} valor={}",
                r.getId(), m.chave, m.nomeEmitente, m.valorTotal);
        return n;
    }

    // ══ Parse simples via XPath — cobre tanto <nfeProc> quanto <NFe> puro ══
    private record Metadados(String chave, String cnpjEmitente, String nomeEmitente,
                             String numero, String modelo,
                             LocalDateTime dataEmissao, BigDecimal valorTotal) {}

    private Metadados extrair(String xml) {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);   // XPath sem prefixo
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            XPath xp = XPathFactory.newInstance().newXPath();

            String chave = tentar(xp, doc, "//infNFe/@Id");
            if (chave != null) chave = chave.replaceFirst("^NFe", "");
            if (chave == null || chave.isBlank()) chave = tentar(xp, doc, "//protNFe/infProt/chNFe");

            String cnpj = tentar(xp, doc, "//emit/CNPJ");
            String nome = tentar(xp, doc, "//emit/xNome");
            String numero = tentar(xp, doc, "//ide/nNF");
            String modelo = tentar(xp, doc, "//ide/mod");
            String dhEmi = tentar(xp, doc, "//ide/dhEmi");
            String vNF = tentar(xp, doc, "//ICMSTot/vNF");

            return new Metadados(
                    chave,
                    cnpj,
                    nome,
                    numero,
                    modelo,
                    parseData(dhEmi),
                    parseValor(vNF)
            );
        } catch (Exception e) {
            log.warn("[Fiscal][Entrada] Falha ao parsear XML: {}", e.getMessage());
            throw new IllegalArgumentException("XML de NF-e mal-formado: " + e.getMessage());
        }
    }

    private String tentar(XPath xp, Document doc, String expr) {
        try {
            String v = xp.evaluate(expr, doc);
            return (v == null || v.isBlank()) ? null : v.trim();
        } catch (Exception e) { return null; }
    }

    private LocalDateTime parseData(String s) {
        if (s == null || s.isBlank()) return null;
        try { return OffsetDateTime.parse(s).toLocalDateTime(); }
        catch (Exception ignore) {}
        try { return ZonedDateTime.parse(s).toLocalDateTime(); }
        catch (Exception ignore) {}
        try { return LocalDateTime.parse(s); } catch (Exception ignore) {}
        return null;
    }

    private BigDecimal parseValor(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.replace(',', '.').trim()); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }
}
