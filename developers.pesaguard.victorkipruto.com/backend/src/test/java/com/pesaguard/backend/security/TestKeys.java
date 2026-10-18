package com.pesaguard.backend.security;

/**
 * A throwaway RSA key pair for tests.
 *
 * <p>NOT A SECRET. Generated for the test suite only and committed deliberately:
 * it protects nothing, and its presence lets the real RS256 sign/verify path run
 * in unit tests, including the negative cases that must reject a token signed by
 * the wrong key. A deployment supplies its own pair from a secrets manager.
 */
public final class TestKeys {

    private TestKeys() {
    }

    /** PKCS#8 private key, base64. */
    public static final String PRIVATE_KEY_BASE64 =
            "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQDUeLJDRlLMLBk9Evn45Rv9/BzRJxEENwYEfBKdo4irUlGQ5h0NQhHtHyR/6i9mdLGgjOLi8tbtfk/dlgzfBuBJ4DARtPi5j49b/NpB2U5kER6Td3H6myoE4waluA6La/6SlQ30QcMGs+RFpz2IVUJkYhyN8TKuMGl8plLQlI5bWSvQCI03D1EiDA5c+FaeLCh+7Ze0JsRAIiXMPE4+uy7AgLk3uzKYCxPhpHyI6R67qVyLQFFmAldEHdpA3TjTReDwnAx2CEdJtExjzeTchR79aFzHK05LuTReM4NWiUCflSBO+P1prYX5haa1uJiRzjLkEW5WwQwKxivZcY/jMlbrAgMBAAECggEAXqQEg4Lnjpp2A4ZYYk1rUo7iJyfHpKD9xTGe+SjGz8kzG9/kQOuVIJImp/OgeXqxRFp9FaololQJLyKPSSi+7yk1QC3kPIN9z/OpJHuvN1OI0xb5Zi+HX/XgGCEMGX5ZPuG1/X7taCbpHz2HYxrXH++z2GX/tSdLVYKgkNgbMQSPSrqR9Io0QUYKc2vIXk7Mwm4qgQmG6GXkoXmNXQJ5s+wlN8pDWUjT2D3cMIFoPDbhhpqeessRTEQMHULyUgDTjLfEWYkO2W1GkoqN/Wp3uG58aKR1lp8349WugJMXpxWRChsKpAj3QmjkMbERHnHIWUzPkwh3Q7jiJJ9itLrKwQKBgQDe0KMY+QNZbbyDvNHVUacxmRBrbV9fXOleaeuJs8stEYvzbR17AabHNLZx0UXjqdzz5fQr47pVZScMeHHHOpJm3LgWekOBru7pJFHCbLDtagJM62aJPYc1yBWM7WTXSkxQO1qQyaGEHiNYSEpd2Xq4S0J8SKnSPPFqndrESMbL1QKBgQD0Ha/9MfGDAQqdjhDLJ7HWZ6CVum5dyPD3BpEua8oiLgckv5sp+yttkcOy5WaKBCAx8ib9Qw6ValFNUL3Pj/lfuHKmNFZXt9mFxDXnS5/P5JXLvpN3J7CjtRPE90PE8CTAXVE1+0nbK9oTQ1xikrJpK89/xZ53gTgdXqdbqv+3vwKBgDZwKykSzeqvkPtuyqWfyYWorAZTNpYKEUpr/owqTV25h8P1yNog0SqiimDMjJLPEZjVeg6vGPt8N0oos0PF37cZ/jAftxacMurrYL5r595ZpC4+5VJqofpV5E4GQjkHghWoKrxtRvUMl/4dy22akQ0t9hR/fF5NXX57CtQiI8/xAoGAEqdyG1cFrY0W0pNYlS2jWU7x5n8oj+IbB8VosrNp7tK1mQlLQhn/Z5AL9E+zVjiafTaT5CaLpr9JFy9kUcgetkHSAQFe17Uk2iP0Ooh83dYJ3FfjzOcriFb9+78pXpM7O7flzMo3Cph/QZmUJbQwDflbEdh4E4m8UAI7p7WZdOECgYADXN9i+IaLRuXJd++yQ1TShkgoH6yzuO5CBYXjrtsVdpSRP2Q7m39FtN8sP31+ua8VzE/A8QdsXog6xU9z8XU3eCaLV/Bo/aXwYS5B9WctWdiRGCT6zN8eBhkFxyVWoYyjnGpvfUXyhQ6ckxqjfvB4J++JENKBVsnNv/zM9hlkWQ==";

    /** X.509 SubjectPublicKeyInfo, base64. */
    public static final String PUBLIC_KEY_BASE64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1HiyQ0ZSzCwZPRL5+OUb/fwc0ScRBDcGBHwSnaOIq1JRkOYdDUIR7R8kf+ovZnSxoIzi4vLW7X5P3ZYM3wbgSeAwEbT4uY+PW/zaQdlOZBEek3dx+psqBOMGpbgOi2v+kpUN9EHDBrPkRac9iFVCZGIcjfEyrjBpfKZS0JSOW1kr0AiNNw9RIgwOXPhWniwofu2XtCbEQCIlzDxOPrsuwIC5N7symAsT4aR8iOkeu6lci0BRZgJXRB3aQN0400Xg8JwMdghHSbRMY83k3IUe/WhcxytOS7k0XjODVolAn5UgTvj9aa2F+YWmtbiYkc4y5BFuVsEMCsYr2XGP4zJW6wIDAQAB";
}