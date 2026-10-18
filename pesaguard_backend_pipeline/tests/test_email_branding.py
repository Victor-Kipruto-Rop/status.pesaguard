from email_branding import render_branded_email_html


def test_branded_email_has_pesaguard_logo_green_theme_and_escaped_subject():
    rendered = render_branded_email_html(
        "Account <verified>",
        html_body=(
            "<p>Your account is ready.</p>"
            '<a href="https://pesaguard.co.ke/" '
            'style="display:inline-block;padding:12px;background-color:#117A52;'
            'color:#FFFFFF;text-decoration:none;">Continue</a>'
        ),
        text_body="Your account is ready.",
    )

    assert "<title>Account &lt;verified&gt;</title>" in rendered
    assert "https://pesaguard.co.ke/logo/logo.svg" in rendered
    assert 'alt="PesaGuard"' in rendered
    assert "#0B2E24" in rendered
    assert "#2ECC87" in rendered
    assert "<p>Your account is ready.</p>" in rendered
    assert 'href="https://pesaguard.co.ke/"' in rendered
    assert "background-color:#117A52" in rendered
    assert "role=\"presentation\"" in rendered
    assert "pesaguard.co.ke" in rendered


def test_branded_email_extracts_existing_document_body_without_nesting_documents():
    rendered = render_branded_email_html(
        "Reconciliation report",
        html_body=(
            "<!DOCTYPE html><html><head><title>Old</title></head>"
            "<body><h2>Reconciliation report</h2><p>Report content</p></body></html>"
        ),
        text_body="Report content",
    )

    assert rendered.count("<html") == 1
    assert rendered.count("<body") == 1
    assert "<h2>Reconciliation report</h2><p>Report content</p>" in rendered
    assert "<title>Old</title>" not in rendered


def test_branded_email_escapes_plain_text_when_no_html_template_is_supplied():
    rendered = render_branded_email_html(
        "Security notice",
        text_body="First line <unsafe>\nSecond line & done",
        preheader="A short security update",
    )

    assert "A short security update" in rendered
    assert "First line &lt;unsafe&gt;<br>" in rendered
    assert "Second line &amp; done" in rendered
    assert "<unsafe>" not in rendered


def test_branded_email_is_idempotent_and_sanitizes_existing_branded_markup():
    first_render = render_branded_email_html(
        "Reconciliation complete",
        html_body="<p>Your reconciliation is complete.</p>",
        text_body="Your reconciliation is complete.",
    )
    existing_body = first_render.replace(
        "</body>",
        '<script>alert("unsafe")</script></body>',
    )

    rendered = render_branded_email_html(
        "Reconciliation complete",
        html_body=existing_body,
        text_body="Your reconciliation is complete.",
    )

    assert rendered.count("<html") == 1
    assert rendered.count("<body") == 1
    assert rendered.count("https://pesaguard.co.ke/logo/logo.svg") == 1
    assert rendered.count("You are receiving this email from PesaGuard.") == 1
    assert 'data-pesaguard-email-preheader="v1"' in rendered
    assert "<script>" not in rendered
    assert "alert(" not in rendered
    assert "<p>Your reconciliation is complete.</p>" in rendered


def test_branded_email_removes_unsafe_markup_attributes_and_urls():
    rendered = render_branded_email_html(
        "Notice",
        html_body=(
            '<p onclick="run()">Safe text</p>'
            '<script>alert("no")</script>'
            '<a href="javascript:run()">Unsafe link</a>'
            '<img src="https://tracker.example/pixel.png" alt="tracker">'
            '<span style="color:#0B2E24;background-image:url(https://bad.test/x);'
            'background:expression(run())">Styled</span>'
        ),
        text_body="Safe text",
    )

    assert "onclick" not in rendered
    assert "<script>" not in rendered
    assert 'href="javascript:' not in rendered
    assert "tracker.example" not in rendered
    assert "background-image" not in rendered
    assert "url(" not in rendered
    assert "expression" not in rendered
    assert "alert(" not in rendered
    assert "<p>Safe text</p>" in rendered
    assert "<a>Unsafe link</a>" in rendered
