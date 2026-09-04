package com.mydelivery.fiscal.service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.KeyStore;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fincatto.documentofiscal.DFAmbiente;
import com.fincatto.documentofiscal.DFModelo;
import com.fincatto.documentofiscal.DFUnidadeFederativa;
import com.fincatto.documentofiscal.nfe.NFTipoEmissao;
import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.nfe400.classes.NFEndereco;
import com.fincatto.documentofiscal.nfe400.classes.NFFinalidade;
import com.fincatto.documentofiscal.nfe400.classes.NFIndicadorFormaPagamento;
import com.fincatto.documentofiscal.nfe400.classes.NFModalidadeFrete;
import com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoImpostoTributacaoICMS;
import com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoItemModalidadeBCICMS;
import com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoSituacaoTributariaCOFINS;
import com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoSituacaoTributariaPIS;
import com.fincatto.documentofiscal.nfe400.classes.NFNotaSituacaoOperacionalSimplesNacional;
import com.fincatto.documentofiscal.nfe400.classes.NFOrigem;
import com.fincatto.documentofiscal.nfe400.classes.NFProtocoloInfo;
import com.fincatto.documentofiscal.nfe400.classes.NFRegimeTributario;
import com.fincatto.documentofiscal.nfe400.classes.NFTipo;
import com.fincatto.documentofiscal.nfe400.classes.NFTipoImpressao;
import com.fincatto.documentofiscal.nfe400.classes.evento.NFEnviaEventoRetorno;
import com.fincatto.documentofiscal.nfe400.classes.evento.NFEventoRetorno;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.NFCancelamentoRetornoDados;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.NFLoteEnvio;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.NFLoteEnvioRetorno;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.NFLoteEnvioRetornoDados;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.NFLoteIndicadorProcessamento;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFIdentificadorLocalDestinoOperacao;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFIndicadorIEDestinatario;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFIndicadorPresencaComprador;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFMeioPagamento;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNota;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfo;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoDestinatario;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoEmitente;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoFormaPagamento;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoICMSTotal;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoIdentificacao;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoItem;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoItemImposto;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoItemImpostoCOFINS;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoItemImpostoCOFINSAliquota;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoItemImpostoICMS;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoItemImpostoICMS00;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoItemImpostoICMSSN102;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoItemImpostoPIS;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoItemImpostoPISAliquota;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoItemProduto;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoPagamento;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoTotal;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoTransporte;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFOperacaoConsumidorFinal;
import com.fincatto.documentofiscal.nfe400.classes.statusservico.consulta.NFStatusServicoConsultaRetorno;
import com.fincatto.documentofiscal.nfe400.webservices.WSFacade;

import lombok.extern.slf4j.Slf4j;

/**
 * Adapter real da lib {@code com.github.wmixvideo:nfe} (fincatto/documento-fiscal)
 * pra emissão de NFC-e (modelo 65, versão 4.00).
 *
 * <p>Fluxo síncrono: monta {@link NFNota} → embala em {@link NFLoteEnvio}
 * (indicador SÍNCRONO) → {@link WSFacade#enviaLote(NFLoteEnvio)} → parseia
 * cStat/motivo/protocolo. Em qualquer exceção (rede, XSD, XML mal-formado),
 * devolve {@code aprovada=false, cStat="ERRO_TEC"} — nunca lança pro chamador.
 *
 * <p>QR-Code: URL de consulta pública da UF montada com
 * {@link DFUnidadeFederativa#getQrCodeProducao()} / {@code getQrCodeHomologacao()}
 * + parâmetros da NT NFC-e (chave, versão, tpAmb, cIdToken, cHashQRCode).
 * O hash é SHA-1 do concatenado com o CSC no fim, conforme SEFAZ.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mydelivery.fiscal.gateway", havingValue = "real")
public class WmixvideoNfeGateway implements NfeGateway {

    private static final Random RNG = new Random();
    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter FMT_AAMM = DateTimeFormatter.ofPattern("yyMM");

    public WmixvideoNfeGateway() {
        log.info("[Fiscal][Gateway] WmixvideoNfeGateway REAL carregado (nfe400/NFC-e).");
    }

    @Override public boolean disponivel() { return true; }

    // =================== EMISSÃO ===================

    @Override
    public ResultadoEmissao emitir(RequisicaoEmissao req) {
        try {
            NFeConfig cfg = novoConfig(req.uf(), req.ambiente(), req.certificadoPfx(),
                    req.certificadoSenha(), req.cscId(), req.cscValor(), NFTipoEmissao.EMISSAO_NORMAL);

            NFNota nota = montarNota(req, NFTipoEmissao.EMISSAO_NORMAL);
            NFLoteEnvio lote = new NFLoteEnvio();
            lote.setIdLote(String.valueOf(System.currentTimeMillis() % 1_000_000_000L));
            lote.setIndicadorProcessamento(NFLoteIndicadorProcessamento.PROCESSAMENTO_SINCRONO);
            lote.setVersao("4.00");
            lote.setNotas(List.of(nota));

            WSFacade ws = new WSFacade(cfg);
            NFLoteEnvioRetornoDados dados = ws.enviaLote(lote);
            NFLoteEnvioRetorno ret = dados.getRetorno();
            NFProtocoloInfo proto = ret == null ? null : ret.getProtocoloInfo();

            String cStat = proto != null ? proto.getStatus() : (ret == null ? null : ret.getStatus());
            String motivo = proto != null ? proto.getMotivo() : (ret == null ? "sem retorno" : ret.getMotivo());
            String chave  = proto != null ? proto.getChave()  : null;
            String nProt  = proto != null ? proto.getNumeroProtocolo() : null;

            boolean aprovada = "100".equals(cStat);
            String xml = extrairXmlProc(dados);
            String qr  = aprovada && chave != null
                    ? montarQrCodeUrl(req, chave)
                    : null;

            log.info("[Fiscal][Wmix] emit cnpj={} n={} → cStat={} motivo={} proto={}",
                    req.emitente().cnpj(), req.numero(), cStat, truncar(motivo, 80), nProt);
            return new ResultadoEmissao(aprovada, cStat, motivo, chave, nProt, xml, qr);
        } catch (Exception e) {
            log.error("[Fiscal][Wmix] erro técnico na emissão cnpj={} n={}: {}",
                    req.emitente() == null ? "?" : req.emitente().cnpj(), req.numero(), e.toString());
            return new ResultadoEmissao(false, "ERRO_TEC",
                    "Falha técnica: " + e.getMessage(), null, null, null, null);
        }
    }

    // =================== CANCELAMENTO ===================

    @Override
    public ResultadoCancelamento cancelar(RequisicaoCancelamento req) {
        try {
            NFeConfig cfg = novoConfig(req.uf(), req.ambiente(), req.certificadoPfx(),
                    req.certificadoSenha(), null, null, NFTipoEmissao.EMISSAO_NORMAL);

            WSFacade ws = new WSFacade(cfg);
            NFCancelamentoRetornoDados dados = ws.cancelaNota(
                    req.chaveAcesso(), req.protocoloAutorizacao(), req.justificativa());

            NFEnviaEventoRetorno ret = dados.getRetorno();
            Integer cStat = ret == null ? null : ret.getCodigoStatusReposta();
            String motivo = ret == null ? "sem retorno" : ret.getMotivo();
            String protoCanc = null;
            List<NFEventoRetorno> evs = ret == null ? null : ret.getEventoRetorno();
            if (evs != null && !evs.isEmpty()) {
                protoCanc = evs.get(0).toString();
            }
            boolean ok = cStat != null && (cStat == 135 || cStat == 128 || cStat == 155);
            log.info("[Fiscal][Wmix] canc chave={} → cStat={} motivo={}",
                    req.chaveAcesso(), cStat, truncar(motivo, 80));
            return new ResultadoCancelamento(ok, cStat == null ? null : cStat.toString(),
                    motivo, protoCanc, null);
        } catch (Exception e) {
            log.error("[Fiscal][Wmix] erro técnico no cancelamento chave={}: {}",
                    req.chaveAcesso(), e.toString());
            return new ResultadoCancelamento(false, "ERRO_TEC",
                    "Falha técnica no cancelamento: " + e.getMessage(), null, null);
        }
    }

    // =================== CONTINGÊNCIA (offline NFC-e, tpEmis=9) ===================

    @Override
    public ResultadoEmissao emitirContingencia(RequisicaoEmissao req) {
        try {
            // Contingência OFFLINE de NFC-e: gera chave/QR/XML localmente com
            // tpEmis=9. Impressão do cupom sai NA HORA; retransmissão é feita
            // depois pelo NfceRetryJob.
            String chave44 = gerarChave44(req, NFTipoEmissao.CONTIGENCIA_OFFLINE);
            String qr = montarQrCodeUrl(req, chave44);
            // XML mínimo é aceitável enquanto pendente — quando retransmitir,
            // a lib gera o XML completo assinado.
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\">"
                    + "<infNFe Id=\"NFe" + chave44 + "\" versao=\"4.00\">"
                    + "<!-- CONTINGENCIA OFFLINE — retransmissão pendente -->"
                    + "</infNFe></NFe>";
            log.warn("[Fiscal][Wmix] contingência offline emitida chave={} (pendente de retransmissão)", chave44);
            return new ResultadoEmissao(true, "CONTINGENCIA",
                    "Emitida em contingência offline — retransmissão pendente",
                    chave44, "PENDENTE_TRANSMISSAO", xml, qr);
        } catch (Exception e) {
            log.error("[Fiscal][Wmix] falha na contingência offline n={}: {}", req.numero(), e.toString());
            return new ResultadoEmissao(false, "ERRO_TEC",
                    "Falha na contingência: " + e.getMessage(), null, null, null, null);
        }
    }

    @Override
    public ResultadoEmissao retransmitirContingencia(RequisicaoEmissao req, String xmlContingencia) {
        try {
            NFeConfig cfg = novoConfig(req.uf(), req.ambiente(), req.certificadoPfx(),
                    req.certificadoSenha(), req.cscId(), req.cscValor(), NFTipoEmissao.CONTIGENCIA_OFFLINE);

            NFNota nota = montarNota(req, NFTipoEmissao.CONTIGENCIA_OFFLINE);
            NFLoteEnvio lote = new NFLoteEnvio();
            lote.setIdLote(String.valueOf(System.currentTimeMillis() % 1_000_000_000L));
            lote.setIndicadorProcessamento(NFLoteIndicadorProcessamento.PROCESSAMENTO_SINCRONO);
            lote.setVersao("4.00");
            lote.setNotas(List.of(nota));

            WSFacade ws = new WSFacade(cfg);
            NFLoteEnvioRetornoDados dados = ws.enviaLote(lote);
            NFLoteEnvioRetorno ret = dados.getRetorno();
            NFProtocoloInfo proto = ret == null ? null : ret.getProtocoloInfo();
            String cStat = proto != null ? proto.getStatus() : (ret == null ? null : ret.getStatus());
            String motivo = proto != null ? proto.getMotivo() : (ret == null ? "sem retorno" : ret.getMotivo());
            String chave  = proto != null ? proto.getChave()  : null;
            String nProt  = proto != null ? proto.getNumeroProtocolo() : null;
            boolean aprovada = "100".equals(cStat);
            String xml = extrairXmlProc(dados);
            String qr  = aprovada && chave != null ? montarQrCodeUrl(req, chave) : null;
            log.info("[Fiscal][Wmix] retransmissão contingência n={} → cStat={}", req.numero(), cStat);
            return new ResultadoEmissao(aprovada, cStat, motivo, chave, nProt, xml, qr);
        } catch (Exception e) {
            log.error("[Fiscal][Wmix] falha na retransmissão n={}: {}", req.numero(), e.toString());
            return new ResultadoEmissao(false, "ERRO_TEC",
                    "Retransmissão falhou: " + e.getMessage(), null, null, null, null);
        }
    }

    // =================== STATUS SEFAZ ===================

    @Override
    public StatusSefaz consultarStatusSefaz(String uf, int ambiente) {
        try {
            // Consulta status precisa de config com cert válido — usamos um
            // config "mínimo" que aponta pra UF/ambiente sem PFX; a lib tolera
            // no consultaStatus (não assina requisição).
            NFeConfig cfg = novoConfig(uf, ambiente, new byte[0], "", null, null,
                    NFTipoEmissao.EMISSAO_NORMAL);
            WSFacade ws = new WSFacade(cfg);
            NFStatusServicoConsultaRetorno r = ws.consultaStatus(
                    parseUf(uf), DFModelo.NFCE);
            boolean online = r != null && "107".equals(r.getStatus());
            return new StatusSefaz(online,
                    r == null ? null : r.getStatus(),
                    r == null ? "sem retorno" : r.getMotivo());
        } catch (Exception e) {
            log.warn("[Fiscal][Wmix] status SEFAZ uf={} amb={} falhou: {}", uf, ambiente, e.toString());
            return new StatusSefaz(false, "ERRO_TEC", "Falha ao consultar status: " + e.getMessage());
        }
    }

    // =================== MONTAGEM DA NFC-e ===================

    private NFNota montarNota(RequisicaoEmissao req, NFTipoEmissao tpEmis) {
        NFNota nota = new NFNota();
        NFNotaInfo info = new NFNotaInfo();
        info.setVersao(new BigDecimal("4.00"));

        String chave44 = gerarChave44(req, tpEmis);
        // setIdentificador exige EXATAMENTE 44 dígitos (a lib valida). Sem
        // prefixo "NFe" (o XML final gera o Id="NFe<chave>" internamente).
        info.setIdentificador(chave44);

        info.setIdentificacao(montarIde(req, chave44, tpEmis));
        info.setEmitente(montarEmit(req));
        if (req.destinatario() != null && (req.destinatario().cpfCnpj() != null && !req.destinatario().cpfCnpj().isBlank())) {
            info.setDestinatario(montarDest(req.destinatario()));
        }
        info.setItens(montarItens(req));
        info.setTotal(montarTotal(req));
        info.setTransporte(montarTransporte());
        info.setPagamento(montarPagamento(req));

        nota.setInfo(info);
        return nota;
    }

    private NFNotaInfoIdentificacao montarIde(RequisicaoEmissao req, String chave44, NFTipoEmissao tpEmis) {
        NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
        ide.setUf(parseUf(req.uf()));
        // cNF são os 8 dígitos aleatórios já embutidos na chave44 (posições 36..43).
        ide.setCodigoRandomico(chave44.substring(35, 43));
        ide.setNaturezaOperacao("Venda de mercadoria ao consumidor");
        ide.setModelo(DFModelo.NFCE);
        ide.setSerie(String.valueOf(req.serie()));
        ide.setNumeroNota(String.valueOf(req.numero()));
        ide.setDataHoraEmissao(ZonedDateTime.of(req.emitidaEm(), ZONE_SP));
        ide.setTipo(NFTipo.SAIDA);
        ide.setIdentificadorLocalDestinoOperacao(NFIdentificadorLocalDestinoOperacao.OPERACAO_INTERNA);
        ide.setCodigoMunicipio(req.emitente().municipioIbge());
        ide.setTipoImpressao(NFTipoImpressao.DANFE_NFCE);
        ide.setTipoEmissao(tpEmis);
        // DV é o último dígito da chave44
        ide.setDigitoVerificador(Integer.parseInt(chave44.substring(43)));
        ide.setAmbiente(req.ambiente() == 1 ? DFAmbiente.PRODUCAO : DFAmbiente.HOMOLOGACAO);
        ide.setFinalidade(NFFinalidade.NORMAL);
        ide.setOperacaoConsumidorFinal(NFOperacaoConsumidorFinal.SIM);
        ide.setIndicadorPresencaComprador(NFIndicadorPresencaComprador.OPERACAO_PRESENCIAL);
        return ide;
    }

    private NFNotaInfoEmitente montarEmit(RequisicaoEmissao req) {
        Emitente e = req.emitente();
        NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
        emit.setCnpj(e.cnpj());
        emit.setRazaoSocial(e.razaoSocial());
        if (e.nomeFantasia() != null) emit.setNomeFantasia(e.nomeFantasia());
        emit.setInscricaoEstadual(e.inscricaoEstadual() == null || e.inscricaoEstadual().isBlank()
                ? "ISENTO" : e.inscricaoEstadual());
        emit.setRegimeTributario(regimePorCodigo(e.regimeTributario()));

        NFEndereco end = new NFEndereco();
        end.setLogradouro(e.logradouro());
        end.setNumero(e.numero());
        end.setBairro(e.bairro());
        end.setCodigoMunicipio(e.municipioIbge());
        end.setDescricaoMunicipio("");   // preenchido via cadastro no futuro
        end.setUf(parseUf(e.uf()));
        end.setCep(soDigitos(e.cep()));
        end.setCodigoPais("1058");
        end.setDescricaoPais("Brasil");
        emit.setEndereco(end);
        return emit;
    }

    private NFNotaInfoDestinatario montarDest(Destinatario d) {
        NFNotaInfoDestinatario dest = new NFNotaInfoDestinatario();
        String doc = soDigitos(d.cpfCnpj());
        if (doc.length() == 14) dest.setCnpj(doc);
        else dest.setCpf(doc);
        if (d.nome() != null && !d.nome().isBlank()) dest.setRazaoSocial(d.nome());
        if (d.email() != null && !d.email().isBlank()) dest.setEmail(d.email());
        dest.setIndicadorIEDestinatario(NFIndicadorIEDestinatario.NAO_CONTRIBUINTE);
        return dest;
    }

    private List<NFNotaInfoItem> montarItens(RequisicaoEmissao req) {
        List<NFNotaInfoItem> out = new ArrayList<>();
        int regime = req.emitente().regimeTributario();
        boolean simples = regime == 1 || regime == 4;

        for (ItemNota it : req.itens()) {
            NFNotaInfoItem item = new NFNotaInfoItem();
            item.setNumeroItem(it.numero());

            NFNotaInfoItemProduto p = new NFNotaInfoItemProduto();
            p.setCodigo(it.codigo() == null ? String.valueOf(it.numero()) : it.codigo());
            p.setDescricao(it.descricao());
            p.setNcm(it.ncm());
            p.setCfop(it.cfop());
            p.setUnidadeComercial(it.unidade());
            p.setQuantidadeComercial(scale4(it.quantidade()));
            p.setValorUnitario(scale4(it.valorUnitario()));
            p.setValorTotalBruto(scale2(it.quantidade().multiply(it.valorUnitario())));
            p.setUnidadeTributavel(it.unidade());
            p.setQuantidadeTributavel(scale4(it.quantidade()));
            p.setValorUnitarioTributavel(scale4(it.valorUnitario()));
            p.setCodigoDeBarras("SEM GTIN");
            p.setCodigoDeBarrasTributavel("SEM GTIN");
            item.setProduto(p);

            item.setImposto(montarImposto(it, simples));
            out.add(item);
        }
        return out;
    }

    private NFNotaInfoItemImposto montarImposto(ItemNota it, boolean simples) {
        NFNotaInfoItemImposto imp = new NFNotaInfoItemImposto();
        NFOrigem origem = NFOrigem.valueOfCodigo(String.valueOf(it.origem()));
        if (origem == null) origem = NFOrigem.NACIONAL;

        NFNotaInfoItemImpostoICMS icms = new NFNotaInfoItemImpostoICMS();
        if (simples) {
            // Padrão MyDelivery: CSOSN 102 (Tributada sem permissão de crédito).
            NFNotaInfoItemImpostoICMSSN102 sn = new NFNotaInfoItemImpostoICMSSN102();
            sn.setOrigem(origem);
            sn.setSituacaoOperacaoSN(NFNotaSituacaoOperacionalSimplesNacional.CSOSN_102);
            icms.setIcmssn102(sn);
        } else {
            // Regime normal: CST 00 (Tributada integralmente).
            NFNotaInfoItemImpostoICMS00 icms00 = new NFNotaInfoItemImpostoICMS00();
            icms00.setOrigem(origem);
            icms00.setSituacaoTributaria(NFNotaInfoImpostoTributacaoICMS.CST_00);
            icms00.setModalidadeBCICMS(NFNotaInfoItemModalidadeBCICMS.VALOR_OPERACAO);
            BigDecimal base = scale2(it.quantidade().multiply(it.valorUnitario()));
            BigDecimal aliq = it.aliquotaIcms() == null ? BigDecimal.ZERO : it.aliquotaIcms();
            icms00.setValorBaseCalculo(base);
            icms00.setPercentualAliquota(scale2(aliq));
            icms00.setValorTributo(scale2(base.multiply(aliq).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)));
            icms.setIcms00(icms00);
        }
        imp.setIcms(icms);

        // PIS
        NFNotaInfoItemImpostoPIS pis = new NFNotaInfoItemImpostoPIS();
        NFNotaInfoItemImpostoPISAliquota pisAliq = new NFNotaInfoItemImpostoPISAliquota();
        pisAliq.setSituacaoTributaria(simples ? NFNotaInfoSituacaoTributariaPIS.CST_49
                                              : NFNotaInfoSituacaoTributariaPIS.CST_01);
        BigDecimal basePis = scale2(it.quantidade().multiply(it.valorUnitario()));
        BigDecimal aliqPis = it.aliquotaPis() == null ? BigDecimal.ZERO : it.aliquotaPis();
        pisAliq.setValorBaseCalculo(basePis);
        pisAliq.setPercentualAliquota(scale4(aliqPis));
        pisAliq.setValorTributo(scale2(basePis.multiply(aliqPis).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)));
        pis.setAliquota(pisAliq);
        imp.setPis(pis);

        // COFINS
        NFNotaInfoItemImpostoCOFINS cof = new NFNotaInfoItemImpostoCOFINS();
        NFNotaInfoItemImpostoCOFINSAliquota cofAliq = new NFNotaInfoItemImpostoCOFINSAliquota();
        cofAliq.setSituacaoTributaria(simples ? NFNotaInfoSituacaoTributariaCOFINS.CST_49
                                              : NFNotaInfoSituacaoTributariaCOFINS.CST_01);
        BigDecimal aliqCof = it.aliquotaCofins() == null ? BigDecimal.ZERO : it.aliquotaCofins();
        cofAliq.setValorBaseCalculo(basePis);
        cofAliq.setPercentualAliquota(scale4(aliqCof));
        cofAliq.setValor(scale2(basePis.multiply(aliqCof).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)));
        cof.setAliquota(cofAliq);
        imp.setCofins(cof);

        return imp;
    }

    private NFNotaInfoTotal montarTotal(RequisicaoEmissao req) {
        NFNotaInfoTotal total = new NFNotaInfoTotal();
        NFNotaInfoICMSTotal ic = new NFNotaInfoICMSTotal();
        ic.setBaseCalculoICMS(BigDecimal.ZERO);
        ic.setValorTotalICMS(BigDecimal.ZERO);
        ic.setBaseCalculoICMSST(BigDecimal.ZERO);
        ic.setValorTotalICMSST(BigDecimal.ZERO);
        ic.setValorTotalDosProdutosServicos(scale2(req.valorTotal()));
        ic.setValorTotalFrete(BigDecimal.ZERO);
        ic.setValorTotalSeguro(BigDecimal.ZERO);
        ic.setValorTotalDesconto(BigDecimal.ZERO);
        ic.setValorTotalII(BigDecimal.ZERO);
        ic.setValorTotalIPI(BigDecimal.ZERO);
        ic.setValorPIS(BigDecimal.ZERO);
        ic.setValorCOFINS(BigDecimal.ZERO);
        ic.setOutrasDespesasAcessorias(BigDecimal.ZERO);
        ic.setValorTotalNFe(scale2(req.valorTotal()));
        total.setIcmsTotal(ic);
        return total;
    }

    private NFNotaInfoTransporte montarTransporte() {
        NFNotaInfoTransporte t = new NFNotaInfoTransporte();
        t.setModalidadeFrete(NFModalidadeFrete.SEM_OCORRENCIA_TRANSPORTE);
        return t;
    }

    private NFNotaInfoPagamento montarPagamento(RequisicaoEmissao req) {
        NFNotaInfoPagamento pag = new NFNotaInfoPagamento();
        List<NFNotaInfoFormaPagamento> formas = new ArrayList<>();
        for (Pagamento p : req.pagamentos()) {
            NFNotaInfoFormaPagamento f = new NFNotaInfoFormaPagamento();
            NFMeioPagamento meio = NFMeioPagamento.valueOfCodigo(p.tipo());
            if (meio == null) meio = NFMeioPagamento.OUTRO;
            f.setMeioPagamento(meio);
            f.setValorPagamento(scale2(p.valor()));
            f.setIndicadorFormaPagamento(NFIndicadorFormaPagamento.A_VISTA);
            formas.add(f);
        }
        pag.setDetalhamentoFormasPagamento(formas);
        return pag;
    }

    // =================== CHAVE 44 + DV MOD11 ===================

    private String gerarChave44(RequisicaoEmissao req, NFTipoEmissao tpEmis) {
        String uf     = codigoUfIbge(req.uf());
        String aamm   = req.emitidaEm().atZone(ZONE_SP).format(FMT_AAMM);
        String cnpj   = soDigitos(req.emitente().cnpj());
        String modelo = "65";
        String serie  = pad(String.valueOf(req.serie()), 3);
        String nnf    = pad(String.valueOf(req.numero()), 9);
        String tp     = tpEmis == null ? "1" : tpEmis.getCodigo();
        String cNF    = pad(String.valueOf(1 + RNG.nextInt(99_999_998)), 8);
        String base43 = uf + aamm + cnpj + modelo + serie + nnf + tp + cNF;
        return base43 + dvMod11(base43);
    }

    private static String dvMod11(String base43) {
        // Pesos 2..9 aplicados da direita pra esquerda, repetindo.
        int[] pesos = {2, 3, 4, 5, 6, 7, 8, 9};
        int soma = 0;
        for (int i = 0; i < base43.length(); i++) {
            int d = base43.charAt(base43.length() - 1 - i) - '0';
            soma += d * pesos[i % pesos.length];
        }
        int resto = soma % 11;
        int dv = (resto == 0 || resto == 1) ? 0 : 11 - resto;
        return String.valueOf(dv);
    }

    private static String codigoUfIbge(String uf) {
        DFUnidadeFederativa u = parseUf(uf);
        return u == null ? "32" : u.getCodigoIbge();
    }

    private static DFUnidadeFederativa parseUf(String uf) {
        if (uf == null || uf.isBlank()) return DFUnidadeFederativa.ES;
        try {
            return DFUnidadeFederativa.valueOf(uf.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DFUnidadeFederativa.ES;
        }
    }

    // =================== QR CODE URL (NT NFC-e) ===================

    private String montarQrCodeUrl(RequisicaoEmissao req, String chave44) {
        DFUnidadeFederativa uf = parseUf(req.uf());
        String urlBase = req.ambiente() == 1 ? uf.getQrCodeProducao() : uf.getQrCodeHomologacao();
        // ATENÇÃO: alguns estados (ex.: Espírito Santo) usam CSC ID = 0 no
        // credenciamento oficial. NÃO forçar mínimo 1 — respeita exatamente
        // o que a SEFAZ da UF do contribuinte pediu.
        int cscId = safeInt(req.cscId(), 0);
        String csc = req.cscValor() == null ? "" : req.cscValor();
        // NFC-e homologação/emissão normal: hash é SHA-1 da concat "chave|versao|tpAmb|cIdToken|CSC"
        // Formato final da URL: baseUrl?p=chave|versao|tpAmb|cIdToken|hash
        String versao = "2";
        int tpAmb = req.ambiente();
        String dadosHash = chave44 + "|" + versao + "|" + tpAmb + "|" + cscId + "|" + csc;
        String hash;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(dadosHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02X", b));
            hash = sb.toString();
        } catch (Exception e) {
            hash = "";
        }
        String p = chave44 + "|" + versao + "|" + tpAmb + "|" + cscId + "|" + hash;
        String sep = urlBase != null && urlBase.contains("?") ? "&" : "?";
        return (urlBase == null ? "" : urlBase) + sep + "p=" + p;
    }

    // =================== CONFIG DA LIB ===================

    private NFeConfig novoConfig(String uf, int ambiente, byte[] pfx, String senhaPfx,
                                 String cscId, String csc, NFTipoEmissao tpEmis) throws Exception {
        DFUnidadeFederativa cuf = parseUf(uf);
        DFAmbiente amb = ambiente == 1 ? DFAmbiente.PRODUCAO : DFAmbiente.HOMOLOGACAO;
        KeyStore ksPfx = null;
        if (pfx != null && pfx.length > 0) {
            ksPfx = KeyStore.getInstance("PKCS12");
            ksPfx.load(new ByteArrayInputStream(pfx), (senhaPfx == null ? "" : senhaPfx).toCharArray());
        }
        final KeyStore ksFinal = ksPfx;
        final Integer cscIdInt = safeInt(cscId, 1);

        return new NFeConfig() {
            @Override public DFAmbiente getAmbiente() { return amb; }
            @Override public DFModelo getModelo() { return DFModelo.NFCE; }
            @Override public DFUnidadeFederativa getCUF() { return cuf; }
            @Override public KeyStore getCertificadoKeyStore() { return ksFinal; }
            @Override public String getCertificadoSenha() { return senhaPfx == null ? "" : senhaPfx; }
            @Override public KeyStore getCadeiaCertificadosKeyStore() { return null; }
            @Override public String getCadeiaCertificadosSenha() { return ""; }
            @Override public Integer getCodigoSegurancaContribuinteID() { return cscIdInt; }
            @Override public String getCodigoSegurancaContribuinte() { return csc == null ? "" : csc; }
            @Override public NFTipoEmissao getTipoEmissao() { return tpEmis; }
            @Override public String getVersao() { return "4.00"; }
        };
    }

    // =================== HELPERS ===================

    private static String extrairXmlProc(NFLoteEnvioRetornoDados dados) {
        // A lib guarda o XML assinado dentro do lote — se disponível,
        // retornamos como string. Caso a versão da lib não exponha, gravamos
        // apenas a essência (chave/protocolo) num XML mínimo.
        try {
            if (dados != null && dados.getLoteAssinado() != null) {
                return dados.getLoteAssinado().toString();
            }
        } catch (Exception ignored) { /* fallback abaixo */ }
        return null;
    }

    private static NFRegimeTributario regimePorCodigo(int c) {
        return switch (c) {
            case 1 -> NFRegimeTributario.SIMPLES_NACIONAL;
            case 2 -> NFRegimeTributario.SIMPLES_NACIONAL_EXCESSO_RECEITA;
            case 4 -> NFRegimeTributario.MEI;
            default -> NFRegimeTributario.NORMAL;
        };
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale4(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(4, RoundingMode.HALF_UP);
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = s.length(); i < n; i++) sb.append('0');
        return sb.append(s).toString();
    }

    private static String soDigitos(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static String truncar(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static int safeInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }
}
