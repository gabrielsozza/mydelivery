package com.mydelivery.fiscal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.mydelivery.fiscal.model.NotaEntradaAnexo;
import com.mydelivery.fiscal.repository.NotaEntradaAnexoRepository;
import com.mydelivery.model.Restaurante;

class NotaEntradaAnexoServiceTest {
    @Test
    void fotoJpegDoCelularEhAceitaEAssociadaAoRestaurante() throws Exception {
        var repo = mock(NotaEntradaAnexoRepository.class);
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var restaurante = new Restaurante();
        restaurante.setId(48L);
        byte[] foto = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        var arquivo = new MockMultipartFile("arquivo", "nota-celular.jpeg", "application/octet-stream", foto);

        var salvo = new NotaEntradaAnexoService(repo).salvar(restaurante, arquivo);

        assertThat(salvo.getRestaurante().getId()).isEqualTo(48L);
        assertThat(salvo.getTipoMime()).isEqualTo("image/jpeg");
        assertThat(salvo.getNomeArquivo()).isEqualTo("nota-celular.jpg");
        assertThat(salvo.getConteudo()).isEqualTo(foto);
        verify(repo).save(any());
    }

    @Test
    void reconheceHeicMasRecusaArquivoDisfarcado() {
        byte[] heic = {0, 0, 0, 12, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
        assertThat(NotaEntradaAnexoService.detectarTipo(heic)).isEqualTo("image/heic");
        assertThatThrownBy(() -> NotaEntradaAnexoService.detectarTipo(new byte[20]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Formato não aceito");
    }

    @Test
    void uploadRepetidoRetornaComprovanteExistente() throws Exception {
        var repo = mock(NotaEntradaAnexoRepository.class);
        var restaurante = new Restaurante();
        restaurante.setId(48L);
        var existente = NotaEntradaAnexo.builder().id(5L).restaurante(restaurante).build();
        when(repo.findByRestauranteIdAndSha256(any(), any())).thenReturn(Optional.of(existente));
        byte[] pdf = "%PDF-1.7 foto da nota".getBytes();
        var arquivo = new MockMultipartFile("arquivo", "nota.pdf", "application/pdf", pdf);

        assertThat(new NotaEntradaAnexoService(repo).salvar(restaurante, arquivo)).isSameAs(existente);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).save(any());
    }
}
