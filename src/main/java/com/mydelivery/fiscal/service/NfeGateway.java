package com.mydelivery.fiscal.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Abstração da biblioteca externa de emissão (br.com.swconsultoria:nfe).
 *
 * <p>Todo o resto do módulo fiscal fala com esta interface. Se amanhã a
 * biblioteca mudar API (ou a gente trocar por outra), só a impl muda —
 * orquestração, dados, storage e auditoria ficam iguais.
 */
public interface NfeGateway {

    /** Emitente (loja) — dados que vão pra tag {@code <emit>} do XML. */
    record Emitente(
            String cnpj,
            String razaoSocial,
            String nomeFantasia,
            String inscricaoEstadual,
            String uf,
            String municipioIbge,
            String municipioNome,
            String logradouro,
            String numero,
            String bairro,
            String cep,
            int regimeTributario) {}

    /** Destinatário — pra NFC-e é opcional (só se cliente quer identificar). */
    record Destinatario(
            String cpfCnpj,
            String nome,
            String email) {}

    /** Um item do pedido, com dados fiscais já resolvidos pelo motor. */
    record ItemNota(
            int numero,           // sequencial começando em 1
            String codigo,        // código do produto
            String descricao,
            String ncm,
            String cfop,
            String cst,           // um dos dois (cst OU csosn)
            String csosn,
            int origem,
            String unidade,
            BigDecimal quantidade,
            BigDecimal valorUnitario,
            BigDecimal aliquotaIcms,
            BigDecimal aliquotaPis,
            BigDecimal aliquotaCofins) {}

    /** Dados de pagamento pra NFC-e (obrigatório). */
    record Pagamento(
            String tipo,          // "01"=Dinheiro, "03"=Cartão crédito, "04"=Débito, "17"=PIX
            BigDecimal valor) {}

    /** Tudo que a emissão precisa. */
    record RequisicaoEmissao(
            String uf,
            int ambiente,         // 1=produção, 2=homologação
            int serie,
            long numero,
            LocalDateTime emitidaEm,
            Emitente emitente,
            Destinatario destinatario,     // nullable
            List<ItemNota> itens,
            BigDecimal valorTotal,
            List<Pagamento> pagamentos,
            String cscId,
            String cscValor,
            byte[] certificadoPfx,
            String certificadoSenha) {}

    /** Resposta da SEFAZ, já parseada. */
    record ResultadoEmissao(
            boolean aprovada,
            String cStat,
            String motivo,
            String chaveAcesso,
            String protocolo,
            String xmlAssinado,
            String qrCodeUrl) {}

    /** Emite (síncrono). Nunca lança exceção — sempre devolve um resultado com cStat.
     *  Erro técnico (rede/lib) vira ResultadoEmissao aprovada=false, cStat="ERRO_TEC". */
    ResultadoEmissao emitir(RequisicaoEmissao req);

    /**
     * Cancelamento de NFC-e (evento tipo 110111).
     * SEFAZ aceita cancelamento até 30 min pós-emissão pra NFC-e.
     * A justificativa é OBRIGATÓRIA (15 a 255 chars).
     */
    record RequisicaoCancelamento(
            String uf,
            int ambiente,
            String cnpj,
            String chaveAcesso,
            String protocoloAutorizacao,
            String justificativa,
            int numeroSequencialEvento,
            byte[] certificadoPfx,
            String certificadoSenha) {}

    record ResultadoCancelamento(
            boolean aprovado,
            String cStat,
            String motivo,
            String protocoloCancelamento,
            String xmlCancelamento) {}

    /** Cancela uma nota autorizada. Igual emitir: nunca lança — sempre devolve resultado. */
    ResultadoCancelamento cancelar(RequisicaoCancelamento req);

    /**
     * Emite em CONTINGÊNCIA OFFLINE NFC-e (tpEmis=9). Usado quando a SEFAZ
     * principal está fora do ar. A nota é impressa NA HORA (assinada localmente
     * com QR válido) e depois deve ser retransmitida — o {@code NfceRetryJob}
     * cuida do reenvio automático quando SEFAZ voltar.
     *
     * <p>Diferença pra {@link #emitir}: não fala com SEFAZ (offline), gera
     * chave/QR local, e a nota fica marcada como {@code CONTINGENCIA_EPEC}
     * até ser retransmitida.
     */
    ResultadoEmissao emitirContingencia(RequisicaoEmissao req);

    /** Retransmite uma nota que foi emitida em contingência. Faz o envio real
     *  pra SEFAZ com o XML já assinado. */
    ResultadoEmissao retransmitirContingencia(RequisicaoEmissao req, String xmlContingencia);

    /**
     * Consulta status operacional da SEFAZ da UF (cStat 107 = em operação).
     * Usado pra decidir se emite normal ou entra em contingência antes de tentar.
     * Se der erro de rede/timeout, devolve status=DOWN.
     */
    record StatusSefaz(boolean online, String cStat, String motivo) {}
    StatusSefaz consultarStatusSefaz(String uf, int ambiente);

    /** Verifica se o gateway está pronto (lib carregada). Pra fail-fast na inicialização. */
    boolean disponivel();
}
