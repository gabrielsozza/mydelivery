package com.mydelivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.mydelivery.config.CloudinaryConfig;

class CloudinaryServiceAvifTest {
    @Test
    void aceitaAvifEEntregaComoJpegCompativel() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        CloudinaryConfig config = mock(CloudinaryConfig.class);
        when(config.isConfigured()).thenReturn(true);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), any(Map.class)))
                .thenReturn(Map.of("secure_url", "https://res.cloudinary.com/test/image/upload/foto.jpg"));
        CloudinaryService service = new CloudinaryService(cloudinary, config);
        ReflectionTestUtils.setField(service, "folder", "produtos");
        var arquivo = new MockMultipartFile("file", "lanche.avif", "image/avif", new byte[] {1, 2, 3});

        assertThat(service.upload(arquivo, "rest-48")).endsWith("/foto.jpg");

        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> opcoes = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(any(byte[].class), opcoes.capture());
        assertThat(opcoes.getValue()).containsEntry("format", "jpg").containsEntry("resource_type", "auto");
    }
}
