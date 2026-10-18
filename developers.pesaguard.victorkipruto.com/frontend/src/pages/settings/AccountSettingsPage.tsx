import { getUserMessage } from "../../lib/errors";
import { ShieldCheck, Sparkles, TimerReset, UserCircle2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useAuth } from "../../context/AuthContext";
import {
  getUsername,
  mfaStatus as loadMfaStatus,
  onboardingStatus,
  updateOnboardingProfile,
  updateUsername,
} from "../../lib/authApi";
import { usernameValidationMessage } from "../../lib/usernameValidation";
import { PageHeader } from "../../components/ui/PageHeader";
import {
  DangerZonePage,
} from "./DangerZonePage";
import { PasswordSettingsStep } from "./PasswordSettingsStep";

type VerificationStatus = "verified" | "pending" | "unverified" | "loading" | "unavailable";
type SupportedLanguage = "en-US" | "en-GB" | "sw" | "fr" | "es" | "ar" | "pt-BR" | "de" | "ja";

type LanguageLabel = {
  title: string;
  description: string;
  profile: string;
  updateIdentity: string;
  active: string;
  fullName: string;
  email: string;
  timezone: string;
  language: string;
  verifyEmail: string;
  enterCodeEmail: string;
  confirmCode: string;
  securityNotice: string;
  securityNoticeDetail: string;
  protectedIdentity: string;
  reset: string;
  saveChanges: string;
  verified: string;
  verificationRequired: string;
  notVerified: string;
  verificationOnHold: string;
  emailCodePlaceholder: string;
  emailVerificationSent: string;
  emailConfirmed: string;
  missingCodeEmail: string;
  saveSuccess: string;
  profileHint: string;
  securityHeading?: string;
  securityDescription?: string;
  accountStatus?: string;
  accountStatusDetail?: string;
  identitySecurity?: string;
  twoFactorLabel?: string;
  twoFactorDescription?: string;
  enableMfa?: string;
  backupCodes?: string;
  backupCodesDescription?: string;
  accountOverview?: string;
  profileCard?: string;
  loginActivity?: string;
  verificationQueue?: string;
  lastUpdated?: string;
  avatarAlt?: string;
};

const languageOptions: { value: SupportedLanguage; label: string }[] = [
  { value: "en-US", label: "English (US)" },
  { value: "en-GB", label: "English (UK)" },
  { value: "sw", label: "Swahili" },
  { value: "fr", label: "Français" },
  { value: "es", label: "Español" },
  { value: "ar", label: "العربية" },
  { value: "pt-BR", label: "Português (Brasil)" },
  { value: "de", label: "Deutsch" },
  { value: "ja", label: "日本語" },
];

const translations: Record<SupportedLanguage, LanguageLabel> = {
  "en-US": {
    title: "Account settings",
    description: "Manage your personal profile and sign-in security preferences.",
    profile: "Profile",
    updateIdentity: "Update your identity and contact details",
    active: "ACTIVE",
    fullName: "Full name",
    email: "Email",
    timezone: "Timezone",
    language: "Language",
    verifyEmail: "Verify email",
    enterCodeEmail: "Enter the 6-digit code sent to",
    confirmCode: "Confirm code",
    securityNotice: "Security notice",
    securityNoticeDetail: "Identity updates require verification before they become active.",
    protectedIdentity: "Protected identity",
    reset: "Reset",
    saveChanges: "Save changes",
    verified: "Verified",
    verificationRequired: "Verification required",
    notVerified: "Not verified",
    verificationOnHold: "Verification on hold",
    emailCodePlaceholder: "123456",
    emailVerificationSent: "Verification email sent. Check your inbox for the 6-digit code.",
    emailConfirmed: "Email updated and verified successfully.",
    missingCodeEmail: "Enter the 6-digit code sent to your email to confirm the change.",
    saveSuccess: "Changes saved. Your profile will reflect the update after confirmation.",
    profileHint: "Profile",
    securityHeading: "Security",
    securityDescription: "Protect your account with device checks and verification controls.",
    accountStatus: "Account status",
    accountStatusDetail: "Your identity is protected and ready for secure access.",
    identitySecurity: "Identity security",
    twoFactorLabel: "Multi-factor verification",
    twoFactorDescription: "Use a trusted authenticator or security key to protect sign-in.",
    enableMfa: "Enable MFA",
    backupCodes: "Backup codes",
    backupCodesDescription: "Store recovery codes in a safe place for account recovery.",
    accountOverview: "Account overview",
    profileCard: "Profile card",
    loginActivity: "Recent login activity",
    verificationQueue: "Verification queue",
    lastUpdated: "Last updated",
    avatarAlt: "User avatar",
  },
  "en-GB": {
    title: "Account settings",
    description: "Manage your personal profile and sign-in security preferences.",
    profile: "Profile",
    updateIdentity: "Update your identity and contact details",
    active: "ACTIVE",
    fullName: "Full name",
    email: "Email",
    timezone: "Time zone",
    language: "Language",
    verifyEmail: "Verify email",
    enterCodeEmail: "Enter the 6-digit code sent to",
    confirmCode: "Confirm code",
    securityNotice: "Security notice",
    securityNoticeDetail: "Identity updates require verification before they become active.",
    protectedIdentity: "Protected identity",
    reset: "Reset",
    saveChanges: "Save changes",
    verified: "Verified",
    verificationRequired: "Verification required",
    notVerified: "Not verified",
    verificationOnHold: "Verification on hold",
    emailCodePlaceholder: "123456",
    emailVerificationSent: "Verification email sent. Check your inbox for the 6-digit code.",
    emailConfirmed: "Email updated and verified successfully.",
    missingCodeEmail: "Enter the 6-digit code sent to your email to confirm the change.",
    saveSuccess: "Changes saved. Your profile will reflect the update after confirmation.",
    profileHint: "Profile",
    securityHeading: "Usalama",
    securityDescription: "Lindana akaunti yako kwa ukaguzi wa kifaa na vidhibiti vya uthibitishaji.",
    accountStatus: "Hali ya akaunti",
    accountStatusDetail: "Utambulisho wako umehifadhiwa na uko tayari kwa ufikiaji salama.",
    identitySecurity: "Usalama wa utambulisho",
    twoFactorLabel: "Uthibitishaji wa vipengele vingi",
    twoFactorDescription: "Tumia kihalisi cha amani au ufunguo wa usalama kulinda kuingia.",
    enableMfa: "Washa MFA",
    backupCodes: "Misimbo ya chelezo",
    backupCodesDescription: "Hifadhi misimbo ya kurejesha mahali salama kwa kurejesha akaunti.",
    accountOverview: "Muhtasari wa akaunti",
    profileCard: "Kadi ya profaili",
    loginActivity: "Shughuli ya hivi karibuni ya kuingia",
    verificationQueue: "Foleni ya uthibitishaji",
    lastUpdated: "Iliyosasishwa mwisho",
    avatarAlt: "Picha ya mtumiaji",
  },
  sw: {
    title: "Mipangilio ya akaunti",
    description: "Dhibiti maelezo yako ya kibinafsi, njia za mawasiliano, na mapendeleo ya uthibitishaji.",
    profile: "Profaili",
    updateIdentity: "Sasisha utambulisho wako na maelezo ya mawasiliano",
    active: "INAYOTUMIKA",
    fullName: "Jina kamili",
    email: "Barua pepe",
    timezone: "Saa za eneo",
    language: "Lugha",
    verifyEmail: "Thibitisha barua pepe",
    enterCodeEmail: "Ingiza msimbo wa nambari 6 uliotumwa kwa",
    confirmCode: "Thibitisha msimbo",
    securityNotice: "Taarifa ya usalama",
    securityNoticeDetail: "Identity updates require verification before they become active.",
    protectedIdentity: "Utambulisho uliohifadhiwa",
    reset: "Rudisha",
    saveChanges: "Hifadhi mabadiliko",
    verified: "Imethibitishwa",
    verificationRequired: "Inahitaji uthibitishaji",
    notVerified: "Haijathibitishwa",
    verificationOnHold: "Uthibitishaji umeahirishwa",
    emailCodePlaceholder: "123456",
    emailVerificationSent: "Barua pepe ya uthibitishaji imetumwa. Angalia kikasha chako kwa msimbo wa nambari 6.",
    emailConfirmed: "Barua pepe imebadilishwa na kuthibitishwa kwa mafanikio.",
    missingCodeEmail: "Ingiza msimbo wa nambari 6 uliotumwa kwa barua pepe yako ili kuthibitisha mabadiliko.",
    saveSuccess: "Mabadiliko yamehifadhiwa. Wasifu wako utabadilika baada ya kuthibitishwa.",
    profileHint: "Profaili",
    securityHeading: "Sécurité",
    securityDescription: "Protégez votre compte avec des contrôles d’appareil et de vérification.",
    accountStatus: "Statut du compte",
    accountStatusDetail: "Votre identité est protégée et prête pour un accès sécurisé.",
    identitySecurity: "Sécurité de l’identité",
    twoFactorLabel: "Vérification multi-facteur",
    twoFactorDescription: "Utilisez un authentificateur fiable ou une clé de sécurité pour protéger la connexion.",
    enableMfa: "Activer l’authentification renforcée",
    backupCodes: "Codes de sauvegarde",
    backupCodesDescription: "Conservez les codes de récupération dans un endroit sûr.",
    accountOverview: "Vue d’ensemble du compte",
    profileCard: "Carte de profil",
    loginActivity: "Activité de connexion récente",
    verificationQueue: "File de vérification",
    lastUpdated: "Dernière mise à jour",
    avatarAlt: "Avatar utilisateur",
  },
  fr: {
    title: "Paramètres du compte",
    description: "Gérez votre profil, vos contacts et vos préférences de vérification.",
    profile: "Profil",
    updateIdentity: "Mettez à jour votre identité et vos coordonnées",
    active: "ACTIF",
    fullName: "Nom complet",
    email: "E-mail",
    timezone: "Fuseau horaire",
    language: "Langue",
    verifyEmail: "Vérifier l’e-mail",
    enterCodeEmail: "Entrez le code à 6 chiffres envoyé à",
    confirmCode: "Confirmer le code",
    securityNotice: "Avis de sécurité",
    securityNoticeDetail: "Identity updates require verification before they become active.",
    protectedIdentity: "Identité protégée",
    reset: "Réinitialiser",
    saveChanges: "Enregistrer les modifications",
    verified: "Vérifié",
    verificationRequired: "Vérification requise",
    notVerified: "Non vérifié",
    verificationOnHold: "Vérification en attente",
    emailCodePlaceholder: "123456",
    emailVerificationSent: "Un e-mail de vérification a été envoyé. Vérifiez votre boîte de réception.",
    emailConfirmed: "L’e-mail a été mis à jour et vérifié avec succès.",
    missingCodeEmail: "Entrez le code à 6 chiffres envoyé à votre e-mail pour confirmer le changement.",
    saveSuccess: "Modifications enregistrées. Votre profil sera mis à jour après confirmation.",
    profileHint: "Profil",
  },
  es: {
    title: "Configuración de la cuenta",
    description: "Administra tu perfil personal, tus contactos y tus preferencias de verificación.",
    profile: "Perfil",
    updateIdentity: "Actualiza tu identidad y tus datos de contacto",
    active: "ACTIVO",
    fullName: "Nombre completo",
    email: "Correo electrónico",
    timezone: "Zona horaria",
    language: "Idioma",
    verifyEmail: "Verificar correo",
    enterCodeEmail: "Introduce el código de 6 dígitos enviado a",
    confirmCode: "Confirmar código",
    securityNotice: "Aviso de seguridad",
    securityNoticeDetail: "Identity updates require verification before they become active.",
    protectedIdentity: "Identidad protegida",
    reset: "Restablecer",
    saveChanges: "Guardar cambios",
    verified: "Verificado",
    verificationRequired: "Se requiere verificación",
    notVerified: "No verificado",
    verificationOnHold: "Verificación en espera",
    emailCodePlaceholder: "123456",
    emailVerificationSent: "Se envió un correo de verificación. Revisa tu bandeja de entrada.",
    emailConfirmed: "El correo electrónico se actualizó y verificó correctamente.",
    missingCodeEmail: "Introduce el código de 6 dígitos enviado a tu correo para confirmar el cambio.",
    saveSuccess: "Cambios guardados. Tu perfil se actualizará tras la confirmación.",
    profileHint: "Perfil",
  },
  ar: {
    title: "إعدادات الحساب",
    description: "إدارة ملفك الشخصي، طرق الاتصال، وتفضيلات التحقق.",
    profile: "الملف الشخصي",
    updateIdentity: "قم بتحديث هويتك ومعلومات الاتصال",
    active: "نشط",
    fullName: "الاسم الكامل",
    email: "البريد الإلكتروني",
    timezone: "المنطقة الزمنية",
    language: "اللغة",
    verifyEmail: "تحقق من البريد",
    enterCodeEmail: "أدخل رمز 6 أرقام تم إرساله إلى",
    confirmCode: "تأكيد الرمز",
    securityNotice: "إشعار الأمان",
    securityNoticeDetail: "Identity updates require verification before they become active.",
    protectedIdentity: "هوية محمية",
    reset: "إعادة تعيين",
    saveChanges: "حفظ التغييرات",
    verified: "تم التحقق",
    verificationRequired: "يتطلب التحقق",
    notVerified: "غير محقق",
    verificationOnHold: "التحقق معلق",
    emailCodePlaceholder: "123456",
    emailVerificationSent: "تم إرسال رسالة تحقق إلى بريدك الإلكتروني. تحقق من صندوق الوارد.",
    emailConfirmed: "تم تحديث البريد الإلكتروني والتحقق منه بنجاح.",
    missingCodeEmail: "أدخل رمز 6 أرقام تم إرساله إلى بريدك لتأكيد التغيير.",
    saveSuccess: "تم حفظ التغييرات. سيتم تحديث ملفك بعد التأكيد.",
    profileHint: "الملف الشخصي",
    securityHeading: "الأمان",
    securityDescription: "احمِ حسابك باستخدام فحوصات الجهاز وضوابط التحقق.",
    accountStatus: "حالة الحساب",
    accountStatusDetail: "هويتك محمية وجاهزة للوصول الآمن.",
    identitySecurity: "أمان الهوية",
    twoFactorLabel: "التحقق متعدد العوامل",
    twoFactorDescription: "استخدم مصدقًا موثوقًا أو مفتاح أمان لحماية تسجيل الدخول.",
    enableMfa: "تفعيل MFA",
    backupCodes: "رموز النسخ الاحتياطي",
    backupCodesDescription: "احتفظ برموز الاسترداد في مكان آمن لاستعادة الحساب.",
    accountOverview: "نظرة عامة على الحساب",
    profileCard: "بطاقة الملف الشخصي",
    loginActivity: "نشاط تسجيل الدخول الأخير",
    verificationQueue: "قائمة التحقق",
    lastUpdated: "آخر تحديث",
    avatarAlt: "صورة المستخدم",
  },
  "pt-BR": {
    title: "Configurações da conta",
    description: "Gerencie seu perfil pessoal, contatos e preferências de verificação.",
    profile: "Perfil",
    updateIdentity: "Atualize sua identidade e contatos",
    active: "ATIVO",
    fullName: "Nome completo",
    email: "E-mail",
    timezone: "Fuso horário",
    language: "Idioma",
    verifyEmail: "Verificar e-mail",
    enterCodeEmail: "Digite o código de 6 dígitos enviado para",
    confirmCode: "Confirmar código",
    securityNotice: "Aviso de segurança",
    securityNoticeDetail: "Identity updates require verification before they become active.",
    protectedIdentity: "Identidade protegida",
    reset: "Redefinir",
    saveChanges: "Salvar alterações",
    verified: "Verificado",
    verificationRequired: "Verificação necessária",
    notVerified: "Não verificado",
    verificationOnHold: "Verificação pendente",
    emailCodePlaceholder: "123456",
    emailVerificationSent: "E-mail de verificação enviado. Verifique sua caixa de entrada.",
    emailConfirmed: "E-mail atualizado e verificado com sucesso.",
    missingCodeEmail: "Digite o código de 6 dígitos enviado ao seu e-mail para confirmar a alteração.",
    saveSuccess: "Alterações salvas. Seu perfil será atualizado após a confirmação.",
    profileHint: "Perfil",
  },
  de: {
    title: "Kontoeinstellungen",
    description: "Verwalten Sie Ihr Profil, Ihre Kontakte und Ihre Verifizierungspräferenzen.",
    profile: "Profil",
    updateIdentity: "Aktualisieren Sie Ihre Identität und Kontaktdaten",
    active: "AKTIV",
    fullName: "Vollständiger Name",
    email: "E-Mail",
    timezone: "Zeitzone",
    language: "Sprache",
    verifyEmail: "E-Mail verifizieren",
    enterCodeEmail: "Geben Sie den 6-stelligen Code ein, der gesendet wurde an",
    confirmCode: "Code bestätigen",
    securityNotice: "Sicherheitsmitteilung",
    securityNoticeDetail: "Identity updates require verification before they become active.",
    protectedIdentity: "Geschützte Identität",
    reset: "Zurücksetzen",
    saveChanges: "Änderungen speichern",
    verified: "Verifiziert",
    verificationRequired: "Verifizierung erforderlich",
    notVerified: "Nicht verifiziert",
    verificationOnHold: "Verifizierung ausstehend",
    emailCodePlaceholder: "123456",
    emailVerificationSent: "Bestätigungs-E-Mail gesendet. Bitte prüfen Sie Ihren Posteingang.",
    emailConfirmed: "E-Mail wurde erfolgreich aktualisiert und verifiziert.",
    missingCodeEmail: "Geben Sie den 6-stelligen Code ein, der an Ihre E-Mail gesendet wurde, um die Änderung zu bestätigen.",
    saveSuccess: "Änderungen gespeichert. Ihr Profil wird nach der Bestätigung aktualisiert.",
    profileHint: "Profil",
  },
  ja: {
    title: "アカウント設定",
    description: "プロフィール、連絡先、認証設定を管理します。",
    profile: "プロフィール",
    updateIdentity: "本人情報と連絡先を更新",
    active: "有効",
    fullName: "氏名",
    email: "メールアドレス",
    timezone: "タイムゾーン",
    language: "言語",
    verifyEmail: "メールを確認",
    enterCodeEmail: "次の宛先に送信された6桁コードを入力してください：",
    confirmCode: "コードを確認",
    securityNotice: "セキュリティ通知",
    securityNoticeDetail: "Identity updates require verification before they become active.",
    protectedIdentity: "保護された本人情報",
    reset: "リセット",
    saveChanges: "変更を保存",
    verified: "確認済み",
    verificationRequired: "認証が必要です",
    notVerified: "未確認",
    verificationOnHold: "認証待ち",
    emailCodePlaceholder: "123456",
    emailVerificationSent: "認証メールを送信しました。受信トレイをご確認ください。",
    emailConfirmed: "メールアドレスを更新し、認証しました。",
    missingCodeEmail: "変更を確認するには、メールに送信された6桁コードを入力してください。",
    saveSuccess: "変更を保存しました。確認後にプロフィールが反映されます。",
    profileHint: "プロフィール",
  },
};

const fallbackTimezones = [
  "UTC",
  "Africa/Nairobi",
  "Africa/Lagos",
  "Africa/Cairo",
  "Europe/London",
  "Europe/Paris",
  "America/New_York",
  "America/Los_Angeles",
  "Asia/Dubai",
  "Asia/Kolkata",
  "Asia/Singapore",
  "Asia/Tokyo",
  "Pacific/Auckland",
];

const initialProfile = {
  name: "",
  email: "",
  username: "",
  timezone: "UTC",
  language: "en-US",
};

const timezoneStorageKey = "pesaguard.account.timezone";
const languageStorageKey = "pesaguard.account.language";

export function AccountSettingsPage() {
  const { user, isAuthenticated, reload } = useAuth();
  const [form, setForm] = useState(initialProfile);
  const [emailStatus, setEmailStatus] = useState<VerificationStatus>("loading");
  const [mfaEnabled, setMfaEnabled] = useState<boolean | null>(null);
  const [mfaError, setMfaError] = useState("");
  const [saveState, setSaveState] = useState<string | null>(null);
  const [profileLoading, setProfileLoading] = useState(false);
  const [profileError, setProfileError] = useState<string | null>(null);
  const [usernameLoading, setUsernameLoading] = useState(true);
  const [savedUsername, setSavedUsername] = useState("");
  const [activeWizard, setActiveWizard] = useState<"details" | "password" | "danger">("details");
  const usernameValidationError = form.username !== savedUsername
    ? usernameValidationMessage(form.username, form.email)
    : null;

  const availableTimezones = useMemo(() => {
    if (typeof Intl !== "undefined" && "supportedValuesOf" in Intl) {
      return (Intl.supportedValuesOf("timeZone") as string[]).slice().sort();
    }
    return fallbackTimezones;
  }, []);

  useEffect(() => {
    if (!isAuthenticated) {
      return;
    }

    const storedTimezone = window.localStorage.getItem(timezoneStorageKey);
    const storedLanguage = window.localStorage.getItem(languageStorageKey);
    setForm((current) => ({
      ...current,
      name: user?.displayName ?? "",
      email: user?.email ?? "",
      language: storedLanguage && storedLanguage in translations
        ? storedLanguage as SupportedLanguage
        : initialProfile.language,
      timezone: storedTimezone || initialProfile.timezone,
    }));
    let active = true;
    setUsernameLoading(true);
    void getUsername().then(({ username }) => {
      if (active) {
        setSavedUsername(username);
        setForm((current) => ({ ...current, username }));
      }
    }).catch((error: unknown) => {
      if (active) {
        setProfileError(getUserMessage(error, "Could not load your username."));
      }
    }).finally(() => {
      if (active) setUsernameLoading(false);
    });
    setEmailStatus("loading");
    setMfaEnabled(null);
    setMfaError("");
    void onboardingStatus().then((status) => {
      if (active) setEmailStatus(status.emailVerified ? "verified" : "unverified");
    }).catch((error: unknown) => {
      if (active) {
        setEmailStatus("unavailable");
        setProfileError(getUserMessage(error, "Could not load email verification status."));
      }
    });
    void loadMfaStatus().then((status) => {
      if (active) setMfaEnabled(status.enabled);
    }).catch((error: unknown) => {
      if (active) setMfaError(getUserMessage(error, "Could not load MFA status."));
    });
    return () => {
      active = false;
    };
  }, [isAuthenticated, user]);

  const selectedLanguage = (form.language as SupportedLanguage) || "en-US";
  const t = translations[selectedLanguage] ?? translations["en-US"];

  const emailSummary = useMemo(() => {
    if (emailStatus === "loading") return "Loading…";
    if (emailStatus === "unavailable") return "Unavailable";
    if (emailStatus === "pending") return t.verificationRequired;
    if (emailStatus === "verified") return t.verified;
    return t.notVerified;
  }, [emailStatus, t]);

  function updateField(field: keyof typeof form, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
    if (field === "username") {
      setProfileError(null);
    }
    if (field === "timezone") {
      window.localStorage.setItem(timezoneStorageKey, value);
    }
    if (field === "language") {
      window.localStorage.setItem(languageStorageKey, value);
      document.documentElement.lang = value;
      document.documentElement.setAttribute("dir", value === "ar" ? "rtl" : "ltr");
    }
    setSaveState(null);
  }

  async function saveChanges() {
    if (form.username !== savedUsername && usernameValidationError) {
      setProfileError(usernameValidationError);
      return;
    }
    setProfileError(null);
    setProfileLoading(true);

    try {
      await updateOnboardingProfile(form.name.trim());
      if (form.username !== savedUsername) {
        const updatedUsername = await updateUsername(form.username);
        setSavedUsername(updatedUsername.username);
        setForm((current) => ({ ...current, username: updatedUsername.username }));
      }
      await reload();
      window.localStorage.setItem(timezoneStorageKey, form.timezone);
      window.localStorage.setItem(languageStorageKey, form.language);
      document.documentElement.lang = form.language;
      document.documentElement.setAttribute("dir", form.language === "ar" ? "rtl" : "ltr");
      setSaveState(t.saveSuccess);
    } catch (error) {
      const message = getUserMessage(error, "Unable to save profile changes.");
      setProfileError(message);
      setSaveState(message);
    } finally {
      setProfileLoading(false);
    }
  }

  return (
    <div className="premium-page account-settings-page">
      <PageHeader
        eyebrow="ACCOUNT"
        title={t.title}
        description={t.description}
      />

      <nav className="account-settings-wizard" role="tablist" aria-label="Account settings">
        <button type="button" role="tab" aria-selected={activeWizard === "details"} className={activeWizard === "details" ? "is-active" : ""} onClick={() => setActiveWizard("details")}>
          <UserCircle2 size={16} />My details
        </button>
        <button type="button" role="tab" aria-selected={activeWizard === "password"} className={activeWizard === "password" ? "is-active" : ""} onClick={() => setActiveWizard("password")}>
          <ShieldCheck size={16} />Password
        </button>
        <button type="button" role="tab" aria-selected={activeWizard === "danger"} className={activeWizard === "danger" ? "is-active" : ""} onClick={() => setActiveWizard("danger")}>
          <span aria-hidden="true">!</span>Danger zone
        </button>
      </nav>

      {activeWizard === "details" && <section className="panel account-profile-panel">
        <div className="panel-heading" style={{ marginBottom: "20px" }}>
          <div className="account-profile-heading">
          <div className="account-profile-icon">
            <UserCircle2 size={28} />
            </div>
            <div>
              <h2>{t.profile}</h2>
              <p>{t.updateIdentity}</p>
            </div>
          </div>
          <span className="table-tag">{t.profileHint}</span>
        </div>

        <div className="account-profile-content">
          <div className="account-security-overview">
            <div className="panel account-overview-card">
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "10px" }}>
                <strong>{t.email}</strong>
                <span className="status-label" style={{ color: emailStatus === "verified" ? "var(--signal-green)" : "var(--text-muted)" }}><span className="status-dot" />{emailSummary}</span>
              </div>
              <p style={{ margin: 0, color: "var(--text-muted)", fontSize: "14px" }}>Verification status returned by the onboarding API.</p>
            </div>
            <div className="panel account-overview-card">
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "10px" }}>
                <strong>{t.twoFactorLabel}</strong>
                <ShieldCheck size={16} color={mfaEnabled ? "var(--forest)" : "var(--text-muted)"} />
              </div>
              <strong>{mfaError || (mfaEnabled === null ? "Loading…" : mfaEnabled ? "Enabled" : "Not enabled")}</strong>
              <p style={{ margin: "8px 0 0", color: "var(--text-muted)", fontSize: "14px" }}>MFA status returned by the authentication API.</p>
            </div>
          </div>

          <div className="settings-grid account-profile-fields">
            <div className="setting-row" style={{ gridColumn: "1 / -1" }}>
              <div><strong>{t.fullName}</strong></div>
              <div>
                <input
                  className="field-control"
                  value={form.name}
                  onChange={(event) => updateField("name", event.target.value)}
                  aria-label={t.fullName}
                />
              </div>
            </div>

            <div className="setting-row" style={{ gridColumn: "1 / -1" }}>
              <div>
                <strong>Username</strong>
                <p className="text-muted">Use this username or your email address to sign in.</p>
              </div>
              <div style={{ display: "grid", gap: "5px" }}>
                <input
                  className="field-control"
                  autoComplete="username"
                  value={form.username}
                  onChange={(event) => updateField("username", event.target.value)}
                  aria-label="Username"
                  aria-invalid={Boolean(usernameValidationError) || undefined}
                  aria-describedby={usernameValidationError ? "account-username-error" : undefined}
                  disabled={usernameLoading}
                />
                {usernameValidationError && <p className="form-error" id="account-username-error" role="status">{usernameValidationError}</p>}
              </div>
            </div>

            <div className="setting-row">
              <div><strong>{t.email}</strong></div>
              <div style={{ display: "grid", gap: "8px" }}>
                <input
                  className="field-control"
                  type="email"
                  value={form.email}
                  readOnly
                  aria-label={t.email}
                />
                <span className="status-label" style={{ color: emailStatus === "verified" ? "var(--signal-green)" : "var(--signal-amber)" }}>
                  <span className="status-dot" />{emailSummary}
                </span>
              </div>
            </div>

            <div className="setting-row">
              <div><strong>{t.timezone}</strong></div>
              <div>
                <select className="field-control" value={form.timezone} onChange={(event) => updateField("timezone", event.target.value)} aria-label={t.timezone}>
                  {availableTimezones.map((zone) => (
                    <option key={zone} value={zone}>{zone}</option>
                  ))}
                </select>
              </div>
            </div>

            <div className="setting-row">
              <div><strong>{t.language}</strong></div>
              <div>
                <select className="field-control" value={form.language} onChange={(event) => updateField("language", event.target.value)} aria-label={t.language}>
                  {languageOptions.map((language) => (
                    <option key={language.value} value={language.value}>{language.label}</option>
                  ))}
                </select>
              </div>
            </div>
          </div>

          <p className="text-muted">Timezone and language preferences are stored in this browser. Your profile name, username, and email verification are managed by the system.</p>

          <div className="panel account-security-note">
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "12px", flexWrap: "wrap", marginBottom: "12px" }}>
              <div>
                <strong>{t.securityHeading}</strong>
                <div style={{ color: "var(--text-muted)", fontSize: "14px", marginTop: "4px" }}>{t.securityDescription}</div>
              </div>
              <span className="table-tag">System-managed</span>
            </div>
            <p className="text-muted">This screen does not change sign-in policy. Manage password changes in the Password section.</p>
          </div>

          {saveState && (
            <div role={profileError ? "alert" : "status"} style={{ display: "flex", alignItems: "center", gap: "8px", padding: "12px 14px", background: profileError ? "rgba(197, 44, 44, 0.08)" : "var(--lime-wash)", border: "1px solid var(--line)", borderRadius: profileError ? 0 : "9px", color: "var(--text-muted)", fontSize: "13px" }}>
              {profileError ? <ShieldCheck size={15} color="var(--signal-red)" /> : <Sparkles size={15} color="var(--forest)" />}
              <span>{saveState}</span>
            </div>
          )}

          <div style={{ display: "flex", gap: "10px", justifyContent: "flex-end", alignItems: "center", flexWrap: "wrap" }}>
            <button className="button button--secondary" type="button" onClick={() => setForm((current) => ({
              ...current,
              name: user?.displayName ?? "",
              email: user?.email ?? "",
              username: savedUsername,
            }))}>
              <TimerReset size={14} />{t.reset}
            </button>
            <button className="button button--primary" type="button" onClick={saveChanges} disabled={profileLoading || usernameLoading}>
              {usernameLoading ? "Loading..." : profileLoading ? "Saving..." : t.saveChanges}
            </button>
          </div>
        </div>
      </section>}
      {activeWizard === "password" && <PasswordSettingsStep />}
      {activeWizard === "danger" && <DangerZonePage />}
    </div>
  );
}
