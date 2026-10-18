import { useMemo, useState } from "react";
import { ArrowRight, ChevronDown, Search, X } from "lucide-react";
import { developerFaqGroups } from "./developerFaqs";
import "../../styles/onboarding.css";

export function DeveloperFaqPage() {
  const [searchTerm, setSearchTerm] = useState("");
  const normalizedSearch = searchTerm.trim().toLocaleLowerCase();
  const filteredGroups = useMemo(() => developerFaqGroups
    .map((group) => ({
      ...group,
      faqs: group.faqs.filter(({ question, answer }) =>
        !normalizedSearch
        || question.toLocaleLowerCase().includes(normalizedSearch)
        || answer.some((paragraph) => paragraph.toLocaleLowerCase().includes(normalizedSearch))),
    }))
    .filter((group) => group.faqs.length > 0), [normalizedSearch]);
  const resultCount = filteredGroups.reduce((total, group) => total + group.faqs.length, 0);

  return (
    <main className="onboarding-page onboarding-faq-page">
      <header className="onboarding-topbar">
        <a className="onboarding-brand" href="/" aria-label="PesaGuard developer home">
          <img src="/pesaguard-brand-mark.svg" alt="" width="34" height="36" />
          <span>PesaGuard</span>
        </a>
        <nav className="onboarding-topbar-actions" aria-label="Account navigation">
          <a className="onboarding-faq-button" href="/login">Sign in <ArrowRight size={15} /></a>
        </nav>
      </header>

      <div className="onboarding-faq-page-content">
        <div className="onboarding-faq-toolbar">
          <div className="onboarding-faq-toolbar-copy">
            <h1 id="developer-faq-title">Frequently asked questions</h1>
            <p>Find answers about your account, email verification, sign-in, and sandbox.</p>
          </div>
          <label className="onboarding-faq-search">
            <Search size={18} aria-hidden="true" />
            <span className="visually-hidden">Search FAQs</span>
            <input
              type="search"
              value={searchTerm}
              onChange={(event) => setSearchTerm(event.target.value)}
              placeholder="Search account, verification, sandbox…"
            />
            {searchTerm && <button type="button" onClick={() => setSearchTerm("")} aria-label="Clear FAQ search"><X size={16} /></button>}
          </label>
        </div>

        {!normalizedSearch && <nav className="onboarding-faq-topic-nav" aria-label="FAQ topics">
          {developerFaqGroups.map((group, index) => (
            <a href={`#${group.id}`} key={group.id}>
              <span>0{index + 1}</span>
              <strong>{group.title}</strong>
              <ArrowRight size={14} />
            </a>
          ))}
        </nav>}

        <div className="onboarding-faq-results" aria-live="polite">
          {resultCount > 0
            ? `${resultCount} ${resultCount === 1 ? "answer" : "answers"}${normalizedSearch ? ` for “${searchTerm.trim()}”` : ""}`
            : `No answers found for “${searchTerm.trim()}”`}
        </div>

        {filteredGroups.length > 0 ? <div className="onboarding-faq-groups">
          {filteredGroups.map((group, groupIndex) => (
            <section className="onboarding-faq-group" id={group.id} aria-labelledby={`${group.id}-title`} key={group.id}>
              <header className="onboarding-faq-group-heading">
                <span className="onboarding-faq-group-number">0{groupIndex + 1}</span>
                <div>
                  <h2 id={`${group.id}-title`}>{group.title}</h2>
                  <p>{group.description}</p>
                </div>
                <span className="onboarding-faq-group-count">{group.faqs.length} {group.faqs.length === 1 ? "topic" : "topics"}</span>
              </header>
              <div className="onboarding-faq-page-list">
                {group.faqs.map(({ question, answer }, questionIndex) => (
                  <details key={question}>
                    <summary>
                      <span className="onboarding-faq-question-number">{String(questionIndex + 1).padStart(2, "0")}</span>
                      <span className="onboarding-faq-question-text">{question}</span>
                      <ChevronDown className="onboarding-faq-chevron" size={19} aria-hidden="true" />
                    </summary>
                    <div className="onboarding-faq-answer">
                      {answer.map((paragraph) => <p key={paragraph}>{paragraph}</p>)}
                    </div>
                  </details>
                ))}
              </div>
            </section>
          ))}
        </div> : <div className="onboarding-faq-empty">
          <span className="onboarding-faq-empty-icon"><Search size={20} /></span>
          <h2>No matching answers</h2>
          <p>Try a different term, such as “code”, “workspace”, or “password”.</p>
          <button type="button" className="onboarding-faq-secondary" onClick={() => setSearchTerm("")}>Clear search</button>
        </div>}

      </div>

      <footer className="onboarding-footer">
        <span className="onboarding-footer-brand">PesaGuard developer platform</span>
        <span aria-hidden="true">•</span>
        <span>Account and email verification help</span>
        <nav aria-label="Legal information"><a href="/terms">Terms</a><a href="/privacy">Privacy</a></nav>
      </footer>
    </main>
  );
}
