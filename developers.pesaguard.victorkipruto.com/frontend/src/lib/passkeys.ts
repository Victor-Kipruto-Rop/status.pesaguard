export type PasskeyCredentialJson = {
  id: string;
  rawId: string;
  type: "public-key";
  authenticatorAttachment: string | null;
  clientExtensionResults: AuthenticationExtensionsClientOutputs;
  response: Record<string, string | string[] | null>;
};

export type PasskeyOptions = {
  challengeId: string;
  publicKey: Record<string, unknown>;
};

export function passkeysAvailable(): boolean {
  return window.isSecureContext
    && "PublicKeyCredential" in window
    && "credentials" in navigator;
}

function fromBase64Url(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = window.atob(base64 + "=".repeat((4 - (base64.length % 4)) % 4));
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes.buffer;
}

function toBase64Url(value: ArrayBuffer | null): string | null {
  if (value === null) return null;
  const bytes = new Uint8Array(value);
  let binary = "";
  for (let index = 0; index < bytes.length; index += 1) {
    binary += String.fromCharCode(bytes[index]);
  }
  return window.btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function requireCredential(value: Credential | null): PublicKeyCredential {
  if (value === null) throw new Error("The passkey request was cancelled.");
  if (!(value instanceof PublicKeyCredential)) {
    throw new Error("This browser returned an unsupported credential.");
  }
  return value;
}

function browserPublicKeyOptions(value: Record<string, unknown>): Record<string, unknown> {
  const nested = value.publicKey;
  if (nested !== null && typeof nested === "object" && !Array.isArray(nested)) {
    return nested as Record<string, unknown>;
  }
  return value;
}

function serializeCredential(credential: PublicKeyCredential): PasskeyCredentialJson {
  const response = credential.response;
  const data: Record<string, string | string[] | null> = {
    clientDataJSON: toBase64Url(response.clientDataJSON),
  };
  if ("attestationObject" in response) {
    const attestation = response as AuthenticatorAttestationResponse;
    data.attestationObject = toBase64Url(attestation.attestationObject);
    data.transports = attestation.getTransports?.() ?? [];
  } else if ("authenticatorData" in response && "signature" in response) {
    const assertion = response as AuthenticatorAssertionResponse;
    data.authenticatorData = toBase64Url(assertion.authenticatorData);
    data.signature = toBase64Url(assertion.signature);
    data.userHandle = toBase64Url(assertion.userHandle);
  } else {
    throw new Error("The authenticator returned an incomplete passkey response.");
  }

  return {
    id: credential.id,
    rawId: toBase64Url(credential.rawId) ?? "",
    type: "public-key",
    authenticatorAttachment: credential.authenticatorAttachment,
    clientExtensionResults: credential.getClientExtensionResults(),
    response: data,
  };
}

export async function createPasskey(options: PasskeyOptions): Promise<PasskeyCredentialJson> {
  if (!passkeysAvailable()) {
    throw new Error("Passkeys require a supported browser and a secure connection (HTTPS or localhost).");
  }
  const publicKey = browserPublicKeyOptions(options.publicKey) as Record<string, unknown> & {
    challenge: string;
    user: { id: string; [key: string]: unknown };
    excludeCredentials?: Array<{ id: string; [key: string]: unknown }>;
  };
  const credential = await navigator.credentials.create({
    publicKey: {
      ...publicKey,
      challenge: fromBase64Url(publicKey.challenge),
      user: { ...publicKey.user, id: fromBase64Url(publicKey.user.id) },
      excludeCredentials: publicKey.excludeCredentials?.map((item) => ({
        ...item,
        id: fromBase64Url(item.id),
      })),
    } as PublicKeyCredentialCreationOptions,
  });
  return serializeCredential(requireCredential(credential));
}

export async function getPasskeyAssertion(options: PasskeyOptions): Promise<PasskeyCredentialJson> {
  if (!passkeysAvailable()) {
    throw new Error("Passkeys require a supported browser and a secure connection (HTTPS or localhost).");
  }
  const publicKey = browserPublicKeyOptions(options.publicKey) as Record<string, unknown> & {
    challenge: string;
    allowCredentials?: Array<{ id: string; [key: string]: unknown }>;
  };
  const credential = await navigator.credentials.get({
    publicKey: {
      ...publicKey,
      challenge: fromBase64Url(publicKey.challenge),
      allowCredentials: publicKey.allowCredentials?.map((item) => ({
        ...item,
        id: fromBase64Url(item.id),
      })),
    } as PublicKeyCredentialRequestOptions,
  });
  return serializeCredential(requireCredential(credential));
}
