import { ArrowUpRight, ShieldCheck } from "lucide-react";
import "./developer-legal.css";

type DocumentKind = "terms" | "privacy";

const documents: Record<DocumentKind, {
  eyebrow: string;
  title: string;
  summary: string;
  contactEmail: string;
  sections: { title: string; body: string }[];
}> = {
  terms: {
    eyebrow: "DEVELOPER PLATFORM · TERMS",
    title: "Terms",
    summary: "Terms for access to the PesaGuard developer workspace, APIs, credentials, and sandbox tools.",
    contactEmail: "legal@pesaguard.co.ke",
    sections: [
      {
        title: "1. Scope and acceptance",
        body: "These terms govern your access to and use of the PesaGuard developer platform, including developer accounts, workspaces, projects, environments, APIs, credentials, documentation, and sandbox tools (the “Platform”). By creating an account or using the Platform, you agree to these terms. If you use the Platform on behalf of an organization, you confirm that you are authorized to act for that organization, and “you” includes that organization. Other PesaGuard websites and services may be governed by separate terms.",
      },
      {
        title: "2. Developer accounts and workspaces",
        body: "Keep your account information accurate and current. You are responsible for activity performed through your account and for managing workspace membership, invitations, projects, and environments. Do not share individual sign-in credentials. Notify PesaGuard promptly if you believe an account or workspace has been accessed without authorization. PesaGuard may require verification or restrict access when needed to protect an account or the Platform.",
      },
      {
        title: "3. Credentials and security",
        body: "Protect passwords, MFA recovery codes, API keys, and other credentials from disclosure. Use each credential only for its intended project and environment, grant only the access needed, and do not publish secrets in source repositories, client-side applications, support requests, or public issue trackers. You are responsible for requests made with your credentials. If a credential may be exposed, revoke or rotate it promptly and report the incident through the Platform support channel.",
      },
      {
        title: "4. Sandbox and production environments",
        body: "Use sandbox resources for development and testing. Sandbox data and behavior may be simulated and are not a representation of production outcomes. Production access is separate and may require eligibility checks, review, or approval. Do not use sandbox credentials against production services or production credentials against sandbox services. You are responsible for confirming the environment before making a request.",
      },
      {
        title: "5. API use, limits, and changes",
        body: "Use APIs in accordance with the applicable documentation, authentication requirements, and rate or usage limits presented by the Platform. Do not evade limits, interfere with service operation, probe systems without authorization, or use the APIs to violate applicable law or another party’s rights. API versions, endpoints, limits, and features may change; where practicable, changes that materially affect integrations will be communicated through developer-facing channels.",
      },
      {
        title: "6. Your applications and data",
        body: "You are responsible for your applications, the data you submit, and the permissions and notices required for your use of that data. You must have the rights and authority needed to submit data to the Platform and to direct PesaGuard to process it for the services you request. Do not submit unlawful content or information you are not authorized to provide.",
      },
      {
        title: "7. Third-party services and integrations",
        body: "The Platform may allow you to configure integrations with services operated by third parties. Your use of those services is governed by their own terms and privacy notices. You are responsible for reviewing those terms, authorizing the connection, and managing the data and permissions exchanged through it. PesaGuard is not responsible for the operation of independent third-party services.",
      },
      {
        title: "8. Availability, suspension, and termination",
        body: "The Platform and its features may be updated, interrupted, or discontinued. PesaGuard may limit or suspend access where reasonably necessary to protect users, investigate suspected misuse, comply with law, or address a security or operational risk. You may stop using the Platform and close your account through available account controls or by contacting support. Account closure does not remove obligations or records that must remain under applicable law or operational requirements.",
      },
      {
        title: "9. Intellectual property and feedback",
        body: "PesaGuard and its licensors retain their rights in the Platform, APIs, documentation, and related materials. These terms grant you a limited, non-exclusive, revocable right to use the Platform for building and operating your integrations, subject to these terms. If you provide suggestions or feedback, you permit PesaGuard to use them to improve the Platform without restriction or compensation, to the extent permitted by law.",
      },
      {
        title: "10. Disclaimers and limitation",
        body: "To the extent permitted by law, the Platform is provided on an “as available” basis, and PesaGuard does not warrant uninterrupted or error-free operation or that every feature will meet your requirements. Nothing in these terms excludes rights or liability that cannot lawfully be excluded. To the extent permitted by law, PesaGuard is not liable for indirect, incidental, special, consequential, or punitive loss arising from use of the Platform.",
      },
      {
        title: "11. Changes and contact",
        body: "PesaGuard may update these terms as the Platform changes or as required by law. The current version will be published on this page, and material changes will be communicated through an appropriate developer-platform channel where required. For questions about these terms, email legal@pesaguard.co.ke. For account support, use the support channel linked from the developer platform.",
      },
    ],
  },
  privacy: {
    eyebrow: "DEVELOPER PLATFORM · PRIVACY",
    title: "Privacy",
    summary: "How information is handled when you use the PesaGuard developer workspace, APIs, and related platform features.",
    contactEmail: "privacy@pesaguard.co.ke",
    sections: [
      {
        title: "1. Scope and controller",
        body: "This notice describes information handled when you use the PesaGuard developer platform, including developer accounts, workspaces, projects, environments, APIs, and integrations (the “Platform”). Other PesaGuard websites or services may have separate privacy notices. The PesaGuard entity responsible for your information depends on the service and your location; contact privacy@pesaguard.co.ke for entity or privacy questions.",
      },
      {
        title: "2. Information you provide",
        body: "This may include your name, email address, organization or workspace details, profile information, support messages, and information you provide when creating projects or configuring integrations. Authentication and account-security features also process information needed to register, verify, sign in, manage MFA, and recover or change account credentials. Passwords are handled as authentication secrets and should never be included in support messages.",
      },
      {
        title: "3. Workspace and configuration information",
        body: "The Platform processes organization membership and role information, project and environment settings, API-key metadata, webhook configuration, and integration settings to provide workspace features. Secret values such as API keys are sensitive; the Platform may display newly created secrets only at creation time. Avoid putting personal or confidential information into project names, labels, or other fields unless it is needed for your configuration.",
      },
      {
        title: "4. API requests and technical activity",
        body: "When you use Platform APIs, the service may process request and response metadata, timestamps, endpoint and status information, request identifiers, webhook delivery attempts, and related operational records. The exact content depends on the endpoint and data you submit. Do not send payment-card data, authentication secrets, or other sensitive information unless the relevant API documentation expressly requires it.",
      },
      {
        title: "5. Security, session, and audit information",
        body: "The Platform processes session identifiers and security events to authenticate users, maintain sessions, detect abuse, investigate incidents, and protect accounts and services. Audit records may include actions taken in a workspace, actor and resource identifiers, timestamps, and request identifiers. Access to these records is limited according to operational and security needs.",
      },
      {
        title: "6. How information is used",
        body: "Information is used to create and administer developer accounts and workspaces; authenticate users and enforce access controls; deliver APIs, webhooks, and other requested features; provide support; maintain reliability; prevent fraud, abuse, and security incidents; and comply with legal obligations.",
      },
      {
        title: "7. Sharing and service providers",
        body: "Information may be available to personnel who need it to operate, support, or secure the Platform and to service providers who perform functions for PesaGuard, subject to appropriate restrictions. Information may also be disclosed where required by law, to protect users or the Platform, or in connection with a business transfer. When you connect a third-party integration, information may be exchanged with that provider according to the configuration you authorize and that provider’s terms.",
      },
      {
        title: "8. Retention and deletion",
        body: "Information is retained for as long as needed to provide the Platform, maintain security and operational records, resolve disputes, and meet legal or contractual requirements. Retention periods vary by data type and purpose. Account or workspace deletion may not immediately remove records that must be retained for security, compliance, backup, or dispute-resolution purposes.",
      },
      {
        title: "9. Security and international processing",
        body: "PesaGuard uses administrative, technical, and organizational measures intended to protect information handled by the Platform. No method of transmission or storage is completely secure. Information may be processed in locations where PesaGuard or its service providers operate, subject to applicable safeguards and legal requirements.",
      },
      {
        title: "10. Your choices and rights",
        body: "You can review or update certain profile and workspace information through available account controls. Depending on your location, you may have rights to request access, correction, deletion, restriction, portability, or to object to certain processing. Email privacy@pesaguard.co.ke to submit a privacy request. PesaGuard may need to verify your identity and may retain information where permitted or required by law.",
      },
      {
        title: "11. Children and notice changes",
        body: "The developer platform is intended for people acting in a professional or organizational capacity and is not directed to children. PesaGuard may update this notice when platform features, data practices, or legal requirements change. The current version will be published here, with material changes communicated through an appropriate developer-platform channel where required.",
      },
    ],
  },
};

export function DeveloperLegalPage({ kind }: { kind: DocumentKind }) {
  const document = documents[kind];

  return (
    <main className="developer-legal-page">
      <header className="developer-legal-header">
        <a className="developer-legal-brand" href="/" aria-label="PesaGuard Developer Platform home">
          <img src="/pesaguard-brand-mark.svg" alt="" width="34" height="36" />
          <span>PesaGuard</span>
        </a>
        <nav aria-label="Developer legal pages">
          <a href="/terms" aria-current={kind === "terms" ? "page" : undefined}>Terms</a>
          <a href="/privacy" aria-current={kind === "privacy" ? "page" : undefined}>Privacy</a>
          <a className="developer-legal-signin" href="/login">Sign in <ArrowUpRight size={14} /></a>
        </nav>
      </header>

      <article className="developer-legal-content">
        <p className="developer-legal-eyebrow"><ShieldCheck size={14} /> {document.eyebrow}</p>
        <h1>{document.title}</h1>
        <p className="developer-legal-summary">{document.summary}</p>

        <div className="developer-legal-sections">
          {document.sections.map((section, index) => (
            <section key={section.title}>
              <span className="developer-legal-section-number">{String(index + 1).padStart(2, "0")}</span>
              <div>
                <h2>{section.title}</h2>
                <p>{section.body}</p>
              </div>
            </section>
          ))}
        </div>

        <footer className="developer-legal-footer">
          <span>Developer platform documentation</span>
          <a href={`mailto:${document.contactEmail}`}>Contact {document.contactEmail}</a>
        </footer>
      </article>
    </main>
  );
}
