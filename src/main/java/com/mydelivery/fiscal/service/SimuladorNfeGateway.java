package com.mydelivery.fiscal.service;

import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementação SIMULADORA do {@link NfeGateway} — usada em desenvolvimento
 * e testes end-to-end da orquestração fiscal (números, storage, auditoria,
 * status). NÃO chama a SEFAZ nem assina de verdade.
 *
 * <p>Sempre "aprova" a nota (retorna cStat=100) em homologação. Gera uma
 * chave de acesso pseudo-válida (44 dígitos com prefixo previsível), um
 * protocolo fake e um XML mínimo — permitindo que o fluxo completo rode
 * sem depender da rede SEFAZ nem da lib externa carregada.
 *
 * <p>Marcado {@code @Primary} enquanto a impl real ({@code SwConsultoriaNfeGateway})
 * não estiver validada em produção. Pra desativar o simulador, adicione
 * {@code mydelivery.fiscal.gateway=real} no application.properties.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "mydelivery.fiscal.gateway",
        havingValue = "simulador",
        matchIfMissing = true)
public class SimuladorNfeGateway implements NfeGateway {

    private final SecureRandom rng = new SecureRandom();
    private final boolean habilitado;

    public SimuladorNfeGateway(
            @Value("${mydelivery.fiscal.gateway:simulador}") String modo) {
        this.habilitado = "simulador".equalsIgnoreCase(modo);
        if (habilitado) {
            log.warn("[Fiscal][Gateway] SIMULADOR ATIVO — notas NÃO são enviadas à SEFAZ. "
                    + "Pra ligar a lib real, defina mydelivery.fiscal.gateway=real e valide em ambiente de homologacao.");
        }
    }

    @Override
    public boolean disponivel() { return habilitado; }

    @Override
    public ResultadoEmissao emitir(RequisicaoEmissao req) {
        if (!habilitado) {
            return new ResultadoEmissao(false, "ERRO_TEC",
                    "Simulador desabilitado — configure o gateway real", null, null, null, null);
        }
        // Se qualquer coisa crítica faltar, aborta ANTES de "aprovar" (senão o
        // dono acha que emitiu e não emitiu).
        if (req.certificadoPfx() == null || req.certificadoPfx().length == 0)
            return new ResultadoEmissao(false, "ERRO_TEC", "Certificado ausente", null, null, null, null);
        if (req.cscValor() == null || req.cscValor().isBlank())
            return new ResultadoEmissao(false, "ERRO_TEC", "CSC ausente", null, null, null, null);
        if (req.itens() == null || req.itens().isEmpty())
            return new ResultadoEmissao(false, "ERRO_TEC", "Nota sem itens", null, null, null, null);
        if (req.emitente() == null || req.emitente().cnpj() == null || req.emitente().cnpj().length() != 14)
            return new ResultadoEmissao(false, "ERRO_TEC", "CNPJ do emitente inválido", null, null, null, null);

        String chave = gerarChaveFake(req);
        String protocolo = "SIM" + System.currentTimeMillis();
        String xml = xmlMinimo(req, chave, protocolo);
        String qrUrl = "https://sim.mydeliveryfood.com.br/qrcode?chNFe=" + chave;

        log.info("[Fiscal][SIM] emitida chave={} numero={} valor={}",
                chave, req.numero(), req.valorTotal());
        return new ResultadoEmissao(true, "100", "Autorizado o uso da NF-e (SIMULADO)",
                chave, protocolo, xml, qrUrl);
    }

    @Override
    public ResultadoEmissao emitirContingencia(RequisicaoEmissao req) {
        // Contingência OFFLINE — não fala com SEFAZ. Gera chave/QR local e
        // marca pra retransmissão futura. Nota SAI válida pra imprimir.
        if (!habilitado) return new ResultadoEmissao(false, "ERRO_TEC",
                "Simulador desabilitado", null, null, null, null);
        String chave = gerarChaveFake(req);
        String xml = xmlMinimo(req, chave, "CONTINGENCIA_OFFLINE");
        String qrUrl = "https://sim.mydeliveryfood.com.br/qrcode?chNFe=" + chave;
        log.warn("[Fiscal][SIM][CONT] emitida offline chave={} — retentar transmissao quando SEFAZ voltar", chave);
        return new ResultadoEmissao(true, "CONTINGENCIA", "Emitida em contingência offline (aguarda retransmissão)",
                chave, "PENDENTE_TRANSMISSAO", xml, qrUrl);
    }

    @Override
    public ResultadoEmissao retransmitirContingencia(RequisicaoEmissao req, String xmlContingencia) {
        // Simulador aprova sempre a retransmissão. Real: envia XML já assinado
        // pra SEFAZ e recebe protocolo definitivo.
        if (!habilitado) return new ResultadoEmissao(false, "ERRO_TEC",
                "Simulador desabilitado", null, null, null, null);
        String proto = "SIMRETX" + System.currentTimeMillis();
        log.info("[Fiscal][SIM][CONT] retransmissao OK numero={}", req.numero());
        return new ResultadoEmissao(true, "100", "Autorizado (retransmissão de contingência)",
                gerarChaveFake(req), proto, xmlContingencia, null);
    }

    @Override
    public StatusSefaz consultarStatusSefaz(String uf, int ambiente) {
        // Simulador sempre reporta online. Real: consulta o WS de status
        // e devolve cStat 107 (Em Operação) ou 108/109 (paralisado).
        return new StatusSefaz(true, "107",
                "Servico em Operacao (SIMULADO - " + uf + " amb=" + ambiente + ")");
    }

    @Override
    public ResultadoCancelamento cancelar(RequisicaoCancelamento req) {
        if (!habilitado)
            return new ResultadoCancelamento(false, "ERRO_TEC", "Simulador desabilitado", null, null);
        if (req.chaveAcesso() == null || req.chaveAcesso().length() != 44)
            return new ResultadoCancelamento(false, "ERRO_TEC", "Chave de acesso inválida", null, null);
        if (req.justificativa() == null || req.justificativa().length() < 15)
            return new ResultadoCancelamento(false, "ERRO_TEC",
                    "Justificativa obrigatória (mínimo 15 caracteres — SEFAZ exige)", null, null);
        String protoCanc = "SIMCANC" + System.currentTimeMillis();
        String xmlCanc = "<?xml version=\"1.0\"?><procEventoNFe versao=\"1.00\">"
                + "<!-- SIMULADO — cancelamento fake -->"
                + "<retEvento><infEvento><cStat>135</cStat><nProt>" + protoCanc + "</nProt>"
                + "<chNFe>" + req.chaveAcesso() + "</chNFe></infEvento></retEvento></procEventoNFe>";
        log.info("[Fiscal][SIM] cancelamento OK chave={} protoOriginal={} justif=\"{}\"",
                req.chaveAcesso(), req.protocoloAutorizacao(),
                req.justificativa().length() > 40 ? req.justificativa().substring(0, 40) + "..." : req.justificativa());
        return new ResultadoCancelamento(true, "135",
                "Evento registrado e vinculado à NF-e (SIMULADO)", protoCanc, xmlCanc);
    }

    /**
     * Gera chave 44 dígitos no formato SEFAZ:
     * UF(2) + AAMM(4) + CNPJ(14) + Modelo(2) + Série(3) + Número(9) + tpEmis(1) + cNF(8) + DV(1).
     * DV é dummy no simulador.
     */
    private String gerarChaveFake(RequisicaoEmissao req) {
        String uf = codigoUf(req.uf());
        String aamm = req.emitidaEm().format(DateTimeFormatter.ofPattern("yyMM"));
        String cnpj = req.emitente().cnpj();
        String modelo = "65";                                       // NFC-e
        String serie = pad(String.valueOf(req.serie()), 3);
        String numero = pad(String.valueOf(req.numero()), 9);
        String tpEmis = "1";                                        // normal
        String cNF = pad(String.valueOf(rng.nextInt(100_000_000)), 8);
        String base = uf + aamm + cnpj + modelo + serie + numero + tpEmis + cNF;
        String dv = String.valueOf((int)(Math.abs(base.hashCode()) % 10));
        return base + dv;
    }

    private static String pad(String s, int n) {
        while (s.length() < n) s = "0" + s;
        return s;
    }

    private static String codigoUf(String uf) {
        // Códigos IBGE das UFs (2 dígitos). Fallback 32=ES.
        return switch (uf == null ? "ES" : uf.toUpperCase()) {
            case "AC" -> "12"; case "AL" -> "27"; case "AP" -> "16"; case "AM" -> "13";
            case "BA" -> "29"; case "CE" -> "23"; case "DF" -> "53"; case "ES" -> "32";
            case "GO" -> "52"; case "MA" -> "21"; case "MT" -> "51"; case "MS" -> "50";
            case "MG" -> "31"; case "PA" -> "15"; case "PB" -> "25"; case "PR" -> "41";
            case "PE" -> "26"; case "PI" -> "22"; case "RJ" -> "33"; case "RN" -> "24";
            case "RS" -> "43"; case "RO" -> "11"; case "RR" -> "14"; case "SC" -> "42";
            case "SP" -> "35"; case "SE" -> "28"; case "TO" -> "17";
            default -> "32";
        };
    }

    private String xmlMinimo(RequisicaoEmissao req, String chave, String protocolo) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<nfeProc versao=\"4.00\" xmlns=\"http://www.portalfiscal.inf.br/nfe\">\n");
        sb.append("  <NFe><infNFe Id=\"NFe").append(chave).append("\" versao=\"4.00\">\n");
        sb.append("    <!-- SIMULADO — NÃO É NOTA VÁLIDA -->\n");
        sb.append("    <ide><cUF>").append(codigoUf(req.uf())).append("</cUF>")
          .append("<mod>65</mod><serie>").append(req.serie()).append("</serie>")
          .append("<nNF>").append(req.numero()).append("</nNF>")
          .append("<tpAmb>").append(req.ambiente()).append("</tpAmb></ide>\n");
        sb.append("    <emit><CNPJ>").append(req.emitente().cnpj()).append("</CNPJ>")
          .append("<xNome>").append(esc(req.emitente().razaoSocial())).append("</xNome></emit>\n");
        sb.append("    <total><ICMSTot><vNF>").append(req.valorTotal().toPlainString()).append("</vNF></ICMSTot></total>\n");
        sb.append("  </infNFe></NFe>\n");
        sb.append("  <protNFe><infProt><cStat>100</cStat><nProt>").append(protocolo).append("</nProt>")
          .append("<chNFe>").append(chave).append("</chNFe></infProt></protNFe>\n");
        sb.append("</nfeProc>\n");
        return sb.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }
}
