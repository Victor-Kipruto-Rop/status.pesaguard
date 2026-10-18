export type DeveloperFaq = {
  question: string;
  answer: string[];
};

export type DeveloperFaqGroup = {
  id: string;
  title: string;
  description: string;
  faqs: DeveloperFaq[];
};

export const developerFaqGroups: DeveloperFaqGroup[] = [
  {
    id: "account-access",
    title: "Account and sign-in",
    description: "Creating an account, signing in, and protecting your workspace.",
    faqs: [
      {
        question: "Who can create a PesaGuard developer account?",
        answer: [
          "Public registration must be enabled by a platform administrator before a new account can be created. If registration is disabled, the signup form will report that directly; submitting the form will not create a partial account.",
          "When registration is available, provide your name, email address, password, and acceptance of the developer terms and privacy policy. An optional organization name can be supplied during signup.",
        ],
      },
      {
        question: "What happens when I sign in?",
        answer: [
          "The platform checks the email address and password with the authentication service. Incorrect credentials are rejected and do not start an authenticated session.",
          "If the password is correct but the email is still unverified, sign-in pauses at email verification instead of opening the dashboard. If the account has multi-factor authentication enabled, you must also provide an authenticator code. Accounts with access to more than one workspace are asked to choose one before a workspace session is issued.",
        ],
      },
      {
        question: "Why am I asked to choose a workspace?",
        answer: [
          "A developer account can be a member of more than one organization. When that applies, the sign-in flow shows the workspaces the authenticated account can access and asks you to select the intended one.",
          "The selected workspace determines the organization context for the session. The platform does not silently choose on your behalf when an explicit selection is needed.",
        ],
      },
      {
        question: "Can I use developer resources before verifying my email?",
        answer: [
          "No. A correct password for an unverified account is not enough to establish a session. The verification step must succeed before protected developer-platform functionality is available.",
          "This gate applies to sensitive workspace capabilities such as projects, environments, credentials, API Explorer, webhooks, and usage information. Completing verification does not skip the remaining workspace onboarding steps.",
        ],
      },
      {
        question: "How does multi-factor authentication affect sign-in?",
        answer: [
          "If multi-factor authentication has been enabled for your account, the system first confirms your password and then requires a valid second-factor code. The sign-in form will ask for that code as an additional step.",
          "A correct password alone does not create a session when the second factor is required. If you cannot access your configured authenticator, use the recovery process provided for your account or contact your organization administrator.",
        ],
      },
    ],
  },
  {
    id: "email-verification",
    title: "Email verification",
    description: "Verification codes, their lifetime, resend limits, and common errors.",
    faqs: [
      {
        question: "Why does the platform ask me to verify my email?",
        answer: [
          "Email verification confirms that you can receive messages at the address associated with your developer account. This is required before the platform creates an authenticated developer session for an unverified account.",
          "The verification message contains a one-time six-digit code. Enter the code on the account verification page for the email address you are verifying.",
        ],
      },
      {
        question: "How long is a verification code valid?",
        answer: [
          "A code is valid for 10 minutes from the time the system issues it. The verification page displays the remaining time based on the issuance and expiry times supplied by the server, rather than starting a fresh timer whenever the screen is opened.",
          "Once that expiry time has passed, the code cannot be used, even if it is entered correctly. Request a replacement code when the resend cooldown allows it.",
        ],
      },
      {
        question: "When can I request another code, and what happens to the old one?",
        answer: [
          "There is a 60-second cooldown between code issues for the same account. After the cooldown, request a new code from the verification page.",
          "When the system issues a replacement, it replaces the outstanding code, so the previous code is no longer valid. The replacement receives its own 10-minute validity period. For privacy, the resend endpoint does not reveal whether an email belongs to an account or whether it is already verified.",
        ],
      },
      {
        question: "What if my verification code expires?",
        answer: [
          "Only the verification code expires. Your account is not deleted or disabled because the code timed out; it remains pending email verification.",
          "Wait until the resend cooldown has ended, request another code, and use that newest code before its expiry. You do not need to register a second account just because the first code expired.",
        ],
      },
      {
        question: "What happens if I enter the wrong code too many times?",
        answer: [
          "The system limits incorrect verification attempts. If the configured attempt limit is reached, the current code can no longer be used to complete verification.",
          "Request a replacement code after the resend cooldown and enter the new code carefully. The account remains pending verification; reaching the attempt limit does not delete the account.",
        ],
      },
      {
        question: "I requested a code but cannot find the email. What should I check?",
        answer: [
          "Confirm that the verification page shows the address you intended to verify, then check your inbox, spam, and junk folders for a PesaGuard verification message. The message contains the code and states that it expires after 10 minutes.",
          "If you still cannot find it, use the resend action after its cooldown ends. The endpoint intentionally gives a generic response and does not confirm whether an address is registered or pending verification.",
        ],
      },
    ],
  },
  {
    id: "onboarding-workspace",
    title: "Onboarding and workspace",
    description: "What to expect after verification and how your first sandbox is provisioned.",
    faqs: [
      {
        question: "What happens after I successfully verify my email?",
        answer: [
          "When you verified as part of a sign-in challenge and the sign-in requirements are satisfied, the application continues authentication and takes you into developer onboarding. If you verified through a standalone link or without the active sign-in credentials, return to the sign-in page to establish a session.",
          "The dashboard and onboarding pages check the backend for the account's current setup status. They resume at the next required step rather than assuming that profile, organization, project, or environment setup has already been completed.",
        ],
      },
      {
        question: "What does the onboarding process set up?",
        answer: [
          "The guided flow collects the developer profile information required by the workspace, then provisions the project and its sandbox environment through the system. The selected starter template is applied during that project setup.",
          "Provisioning is performed as a system operation. The page reports the project, environment, sandbox, and credential returned by that operation; it does not treat a visual example or placeholder as a completed workspace.",
        ],
      },
      {
        question: "What kind of API key is created during first-time setup?",
        answer: [
          "The initial onboarding flow creates a sandbox credential scoped to read transactions. It is not a production payment credential and does not grant permission to initiate payments.",
          "The raw key is returned once during provisioning so you can copy it and keep it securely. The workspace confirmation includes the expiry returned by the system; the platform does not show the secret again later.",
        ],
      },
      {
        question: "Can I reset my password if I cannot sign in?",
        answer: [
          "Use the “Forgot password?” action on the sign-in page and submit the email address associated with your account. The platform sends a time-limited reset link when an eligible account matches.",
          "For account privacy, the response is the same whether or not an account exists for the submitted address. A completed password reset revokes the account's existing sessions and refresh-token families, so those sessions must authenticate again with the new password.",
        ],
      },
    ],
  },
];

export const developerFaqs = developerFaqGroups.flatMap<DeveloperFaq>((group) => group.faqs);
