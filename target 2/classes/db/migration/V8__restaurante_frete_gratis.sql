-- Frete grátis a partir de X. Feature opt-in por restaurante.
-- Null = desligado (default, comportamento original — sempre cobra taxa).
ALTER TABLE restaurantes
    ADD COLUMN frete_gratis_apartir_de DECIMAL(10,2) NULL;
