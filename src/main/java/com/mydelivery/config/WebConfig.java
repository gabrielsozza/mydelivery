package com.mydelivery.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Servidor de arquivos LEGADOS apenas — anexos/imagens enviados antes da
 * migração para o Cloudinary que ainda residem em ./uploads/.
 *
 * Uploads NOVOS NÃO escrevem aqui — todo upload é centralizado no Cloudinary
 * (CloudinaryService). Esse handler segue ativo só pra que arquivos legados
 * cujas URLs já estão no banco continuem acessíveis em /uploads/**.
 *
 * Quando todos os anexos/imagens antigos forem regerados ou migrados, este
 * arquivo pode ser removido junto com a pasta ./uploads.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${mydelivery.uploads.dir:./uploads}")
    private String uploadsDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path absolute = Paths.get(uploadsDir).toAbsolutePath().normalize();
        String location = "file:" + absolute.toString() + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
    }
}
