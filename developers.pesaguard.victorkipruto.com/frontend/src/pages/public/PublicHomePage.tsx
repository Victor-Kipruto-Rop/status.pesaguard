import { useEffect } from "react";
import {
  ArrowRight,
  ArrowUpRight,
  BookOpenText,
  Braces,
  Check,
  CircleHelp,
  Code2,
  Database,
  LockKeyhole,
  Menu,
  ShieldCheck,
  Webhook,
  X,
  Zap,
} from "lucide-react";
import "../../styles/public-home.css";

const docs = "https://docs.pesaguard.co.ke";
const statusPage = "https://status.pesaguard.co.ke";

const apiAreas = [
  {
    icon: Database,
    title: "Transactions",
    method: "GET",
    path: "/v1/transactions",
    text: "Explore transaction records and build around clear payment data.",
  },
  {
    icon: Code2,
    title: "Reconciliation",
    method: "POST",
    path: "/v1/reconciliation/runs",
    text: "Create and review reconciliation runs from your integration.",
  },
  {
    icon: Webhook,
    title: "Webhooks",
    method: "POST",
    path: "/v1/webhooks/endpoints",
    text: "Connect event delivery to the systems your team already uses.",
  },
  {
    icon: LockKeyhole,
    title: "Accounts",
    method: "GET",
    path: "/v1/accounts",
    text: "Access account endpoints with documented, scoped credentials.",
  },
];

const resources = [
  {
    icon: BookOpenText,
    title: "Getting started",
    text: "Set up your workspace and make your first sandbox request.",
    href: `${docs}/getting-started/`,
  },
  {
    icon: Braces,
    title: "API reference",
    text: "Review available endpoints, request formats, and responses.",
    href: `${docs}/api-reference/`,
  },
  {
    icon: Webhook,
    title: "Webhook guides",
    text: "Learn how to configure endpoints and work with event delivery.",
    href: `${docs}/webhooks/`,
  },
  {
    icon: CircleHelp,
    title: "FAQs and support",
    text: "Find answers or contact the team when you need a hand.",
    href: "/faqs",
  },
];

function Brand() {
  return (
    <a href="/" className="public-home-brand" aria-label="PesaGuard home">
      <img src="/pesaguard-brand-mark.svg" alt="" aria-hidden="true" />
      <span className="public-home-brand-wordmark"><span>Pesa</span><span>Guard</span></span>
    </a>
  );
}

function NavigationLinks() {
  return (
    <>
      <a href="#about">About</a>
      <a href="#benefits">Benefits</a>
      <a href="#apis">Explore APIs</a>
      <a href="#resources">Resources</a>
      <a href="/faqs">FAQs</a>
      <a href={statusPage}>Service status</a>
    </>
  );
}

export function PublicHomePage() {
  useEffect(() => {
    const targets = document.querySelectorAll<HTMLElement>(
      ".public-home .about-section, .public-home .benefits-section, .public-home .benefit-card, .public-home .api-discovery-section, .public-home .api-area-card, .public-home .onboarding-section, .public-home .onboarding-steps li, .public-home .resources-section, .public-home .resource-card, .public-home .closing-cta",
    );

    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    if (!("IntersectionObserver" in window)) {
      targets.forEach((target) => target.classList.add("is-scroll-visible"));
      return;
    }

    const observer = new IntersectionObserver(
      (entries, activeObserver) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          entry.target.classList.add("is-scroll-visible");
          activeObserver.unobserve(entry.target);
        });
      },
      { threshold: 0.12, rootMargin: "0px 0px -5% 0px" },
    );

    targets.forEach((target) => {
      target.classList.add("scroll-pop");
      observer.observe(target);
    });

    return () => observer.disconnect();
  }, []);

  return (
    <main className="public-home">
      <div className="public-home-announcement">
        <span className="announcement-dot" aria-hidden="true" />
        <span className="announcement-copy">Build and test with PesaGuard’s developer platform</span>
        <a className="announcement-link" href={`${docs}/getting-started/`}>
          Read the getting started guide <ArrowRight size={14} aria-hidden="true" />
        </a>
      </div>

      <header className="public-home-header">
        <Brand />
        <nav className="public-home-nav" aria-label="Main navigation">
          <NavigationLinks />
        </nav>
        <div className="public-home-actions">
          <a className="header-login" href="/login">Log in</a>
          <a className="public-home-button button-primary" href="/register">Join us</a>
        </div>
        <details className="public-home-mobile-nav">
          <summary aria-label="Open navigation menu">
            <Menu className="menu-open-icon" size={21} aria-hidden="true" />
            <X className="menu-close-icon" size={21} aria-hidden="true" />
          </summary>
          <nav aria-label="Mobile navigation">
            <NavigationLinks />
            <a className="mobile-login-link" href="/login">Log in</a>
            <a className="public-home-button button-primary" href="/register">Join us</a>
          </nav>
        </details>
      </header>

      <section className="public-home-hero">
        <div className="hero-copy">
          <h1>Built for teams building with <em>trust.</em></h1>
          <p>
            Connect your product to PesaGuard with clear APIs, a dedicated sandbox,
            and practical guidance from your first request onward.
          </p>
          <div className="public-home-cta">
            <a className="public-home-button button-primary" href="/register">Join us</a>
            <a className="hero-doc-link" href={`${docs}/`}>Explore the docs <ArrowRight size={16} aria-hidden="true" /></a>
          </div>
          <ul className="hero-highlights" aria-label="Platform highlights">
            <li><Check size={15} /> Sandbox-first development</li>
            <li><Check size={15} /> Clear API documentation</li>
          </ul>
        </div>

        <div className="hero-visual" aria-label="Illustrative Java request example">
          <div className="hero-console-frame">
            <div className="hero-console-accents" aria-hidden="true">
              <span className="hero-console-accent hero-console-accent--api"><Webhook size={40} /></span>
              <span className="hero-console-accent hero-console-accent--code"><Code2 size={40} /></span>
            </div>
            <div className="java-console">
              <div className="api-window-top java-console-top">
                <span className="window-dots" aria-hidden="true"><i /><i /><i /></span>
                <span>JAVA REQUEST</span>
                <span className="sandbox-tag">SANDBOX</span>
              </div>
              <div className="api-code java-console-code">
                <div><span className="code-muted">01</span>String apiKey = System.getenv("PESAGUARD_API_KEY");</div>
                <div><span className="code-muted">02</span>HttpClient client = HttpClient.newHttpClient();</div>
                <div><span className="code-muted">03</span>HttpRequest request = HttpRequest.newBuilder()</div>
                <div><span className="code-muted">04</span>  .uri(URI.create("https://api.pesaguard.co.ke/v1/transactions"))</div>
                <div><span className="code-muted">05</span>  .header("Authorization", "Bearer " + apiKey)</div>
                <div><span className="code-muted">06</span>  .GET().build();</div>
                <div><span className="code-muted">07</span>HttpResponse&lt;String&gt; response = client.send(request,</div>
                <div><span className="code-muted">08</span>  HttpResponse.BodyHandlers.ofString());</div>
              </div>
              <div className="api-window-foot java-console-foot">
                Illustrative example · Configure your sandbox credentials
              </div>
            </div>
          </div>
        </div>
      </section>

      <section id="about" className="about-section">
        <div className="about-mark" aria-hidden="true"><ShieldCheck size={27} /></div>
        <div className="about-copy">
          <span className="section-eyebrow">ABOUT PESAGUARD</span>
          <h2>Tools for a more <em>thoughtful</em> integration.</h2>
          <p>
            PesaGuard brings payment and risk-related capabilities into one
            developer workspace. Explore the APIs, test in an isolated sandbox,
            and follow the documentation as your integration takes shape.
          </p>
        </div>
        <a className="text-link" href={`${docs}/`}>Learn about the platform <ArrowRight size={16} /></a>
      </section>

      <section id="benefits" className="benefits-section">
        <div className="section-heading">
          <div>
            <span className="section-eyebrow">WHY BUILD WITH PESAGUARD</span>
            <h2>Everything you need to<br /><em>move forward.</em></h2>
          </div>
          <p>Start with the tools that help you understand, test, and connect your integration.</p>
        </div>
        <div className="benefit-grid">
          <article className="benefit-card">
            <span className="benefit-icon"><ShieldCheck size={21} /></span>
            <span className="benefit-number">01 / TEST SAFELY</span>
            <h3>A sandbox to explore</h3>
            <p>Try requests in an isolated environment before you move toward production.</p>
            <a href={`${docs}/getting-started/`}>Explore sandbox guides <ArrowUpRight size={15} /></a>
          </article>
          <article className="benefit-card">
            <span className="benefit-icon"><Braces size={21} /></span>
            <span className="benefit-number">02 / BUILD CLEARLY</span>
            <h3>APIs with guidance</h3>
            <p>Find endpoint details, request examples, and integration guidance in one place.</p>
            <a href={`${docs}/api-reference/`}>Browse API reference <ArrowUpRight size={15} /></a>
          </article>
          <article className="benefit-card">
            <span className="benefit-icon"><LockKeyhole size={21} /></span>
            <span className="benefit-number">03 / STAY IN CONTROL</span>
            <h3>Workspace-level tools</h3>
            <p>Organize projects and environments from your developer workspace.</p>
            <a href={`${docs}/`}>See the developer docs <ArrowUpRight size={15} /></a>
          </article>
        </div>
      </section>

      <section id="apis" className="api-discovery-section">
        <div className="section-heading">
          <div>
            <span className="section-eyebrow">DISCOVER PESAGUARD APIS</span>
            <h2>Find the right place<br />to <em>start building.</em></h2>
          </div>
          <a className="public-home-button button-outline" href={`${docs}/api-reference/`}>View API reference <ArrowUpRight size={16} /></a>
        </div>
        <div className="api-area-grid">
          {apiAreas.map(({ icon: Icon, title, method, path, text }, index) => (
            <a className="api-area-card" href={`${docs}/api-reference/`} key={path}>
              <span className="api-area-icon"><Icon size={20} /></span>
              <span className="api-area-number">0{index + 1}</span>
              <h3>{title}</h3>
              <p>{text}</p>
              <span className="api-endpoint"><b>{method}</b><code>{path}</code></span>
              <span className="api-area-link">Explore API <ArrowUpRight size={14} /></span>
            </a>
          ))}
        </div>
      </section>

      <section id="get-started" className="onboarding-section">
        <div className="onboarding-copy">
          <span className="section-eyebrow">GET STARTED</span>
          <h2>From account setup<br />to your <em>first request.</em></h2>
          <p>Create an account, confirm your email, and use your workspace to configure a sandbox project.</p>
          <a className="public-home-button button-primary" href="/register">Sign up to get started</a>
        </div>
        <ol className="onboarding-steps">
          <li><span className="onboarding-step-number">01</span><span><strong>Create your account</strong><small>Set up your developer profile and workspace.</small></span><Check size={17} /></li>
          <li><span className="onboarding-step-number">02</span><span><strong>Prepare your sandbox</strong><small>Configure a project and test environment.</small></span><Zap size={17} /></li>
          <li><span className="onboarding-step-number">03</span><span><strong>Make an API request</strong><small>Follow the API reference and sandbox guides.</small></span><ArrowUpRight size={17} /></li>
        </ol>
      </section>

      <section id="resources" className="resources-section">
        <div className="section-heading">
          <div>
            <span className="section-eyebrow">DEVELOPER RESOURCES</span>
            <h2>Good guidance,<br /><em>right when you need it.</em></h2>
          </div>
          <p>Documentation and support to help you take the next step with your integration.</p>
        </div>
        <div className="resource-grid">
          {resources.map(({ icon: Icon, title, text, href }) => (
            <a className="resource-card" href={href} key={title}>
              <span className="resource-icon"><Icon size={19} /></span>
              <span className="resource-copy"><strong>{title}</strong><small>{text}</small></span>
              <ArrowUpRight size={17} className="resource-arrow" />
            </a>
          ))}
        </div>
      </section>

      <section className="closing-cta">
        <div className="closing-cta-icon"><Code2 size={22} /></div>
        <div>
          <span className="section-eyebrow">YOUR INTEGRATION STARTS HERE</span>
          <h2>Ready to build with PesaGuard?</h2>
          <p>Create a developer account and explore the platform in the sandbox.</p>
        </div>
        <a className="public-home-button button-light" href="/register">Join us</a>
      </section>

      <footer className="public-home-footer">
        <div className="footer-main">
          <div className="footer-about">
            <Brand />
            <p>Developer tools and guidance for building with PesaGuard.</p>
            <a className="footer-doc-link" href={`${docs}/`}>Developer documentation <ArrowUpRight size={14} /></a>
          </div>
          <div className="footer-column">
            <strong>Platform</strong>
            <a href="#about">About PesaGuard</a>
            <a href="#benefits">Benefits</a>
            <a href="#apis">Explore APIs</a>
          </div>
          <div className="footer-column">
            <strong>Resources</strong>
            <a href={`${docs}/api-reference/`}>API reference</a>
            <a href={`${docs}/webhooks/`}>Webhook guides</a>
            <a href="/faqs">FAQs and support</a>
          </div>
          <div className="footer-column">
            <strong>Account</strong>
            <a href="/login">Log in</a>
            <a href="/register">Create account</a>
            <a href={statusPage}>Service status</a>
          </div>
        </div>
        <div className="footer-bottom">
          <span>© {new Date().getFullYear()} PesaGuard. All rights reserved.</span>
          <span><ShieldCheck size={14} /> Built for safer digital commerce</span>
          <a href="https://pesaguard.co.ke/contact">Contact PesaGuard <ArrowUpRight size={13} /></a>
        </div>
      </footer>
    </main>
  );
}
