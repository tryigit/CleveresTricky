package cleveres.tricky.cleverestech

object TestKeyboxFixtures {
    val ecPrivateKey =
        """
        -----BEGIN EC PRIVATE KEY-----
        MHcCAQEEIB0ruYIH/2OWTKh/ISJ40MzTNAU/9oSgM2ib5Iq+PyGAoAoGCCqGSM49
        AwEHoUQDQgAEUNDt1IfYqg6s4jnvOwE79H9fwoGZ6g/P4+3a/El4Mvon4/5+8/0k
        OPupTprpEf4C+3y2K96dnRpMWiO5R7lNSA==
        -----END EC PRIVATE KEY-----
        """.trimIndent()

    val certificate =
        """
        -----BEGIN CERTIFICATE-----
        MIIBkjCCATmgAwIBAgIUIgbvls1EjUoOT80ARZlgKglzqfowCgYIKoZIzj0EAwIw
        HjEcMBoGA1UEAwwTQ2xldmVyZXNUcmlja3kgVGVzdDAgFw0yNjA4MDExOTQ2MzFa
        GA8yMTI2MDcwODE5NDYzMVowHjEcMBoGA1UEAwwTQ2xldmVyZXNUcmlja3kgVGVz
        dDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABFDQ7dSH2KoOrOI57zsBO/R/X8KB
        meoPz+Pt2vxJeDL6J+P+fvP9JDj7qU6a6RH+Avt8tivenZ0aTFojuUe5TUijUzBR
        MB0GA1UdDgQWBBQQ3PHoJsA6Twzm59dYpELttMhDwzAfBgNVHSMEGDAWgBQQ3PHo
        JsA6Twzm59dYpELttMhDwzAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA0cA
        MEQCIDjQkoh8nCft0/SCsEKmG1OaPpPZRAtsrBwYT5WXIU9uAiBzii8BmWdGSxPj
        X2thzrVAPbxE8mu+E3rhRv5pQ1zEuQ==
        -----END CERTIFICATE-----
        """.trimIndent()

    val validEcKeyboxXml =
        buildString {
            appendLine("""<?xml version="1.0"?>""")
            appendLine("<AndroidAttestation>")
            appendLine("  <NumberOfKeyboxes>1</NumberOfKeyboxes>")
            appendLine("  <Keybox>")
            appendLine("    <Key algorithm=\"ecdsa\">")
            appendLine("      <PrivateKey>")
            appendLine(ecPrivateKey.prependIndent("        "))
            appendLine("      </PrivateKey>")
            appendLine("      <CertificateChain>")
            appendLine("        <NumberOfCertificates>1</NumberOfCertificates>")
            appendLine("        <Certificate>")
            appendLine(certificate.prependIndent("          "))
            appendLine("        </Certificate>")
            appendLine("      </CertificateChain>")
            appendLine("    </Key>")
            appendLine("  </Keybox>")
            append("</AndroidAttestation>")
        }
}
