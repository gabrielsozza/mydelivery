package com.mydelivery.dto.cardapio;

import java.util.List;

import lombok.Data;

/**
 * Recebido do front quando o cliente confirma a importação após revisar o preview.
 * Permite escolher se substitui o cardápio atual ou só adiciona.
 */
@Data
public class ImportacaoConfirmRequest {

    /** Lista de categorias com produtos (já editada pelo cliente). */
    private List<ImportacaoPreviewDTO.CategoriaImport> categorias;

    /**
     * "adicionar" = mantém cardápio existente + adiciona estes (default)
     * "substituir" = apaga categorias e produtos existentes antes de importar
     */
    private String modo;
}
