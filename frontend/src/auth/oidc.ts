import {
  UserManager,
  WebStorageStateStore,
  type User,
} from 'oidc-client-ts'

const applicationOrigin = window.location.origin

export const oidcSettings = {
  authority: import.meta.env.VITE_OIDC_AUTHORITY ?? applicationOrigin,
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID ?? 'emporia-web',
  redirect_uri: `${applicationOrigin}/auth/callback`,
  post_logout_redirect_uri: `${applicationOrigin}/auth/logout-callback`,
  response_type: 'code',
  scope: 'openid profile',
  loadUserInfo: true,
  automaticSilentRenew: false,
  monitorSession: false,
  redirectMethod: 'replace' as const,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
}

export const userManager = new UserManager(oidcSettings)

let signInCallback: Promise<User> | undefined
let signOutCallback: Promise<void> | undefined

export function completeSignIn(): Promise<User> {
  signInCallback ??= userManager.signinRedirectCallback()
  return signInCallback
}

export function completeSignOut(): Promise<void> {
  signOutCallback ??= userManager.signoutRedirectCallback().then(() => undefined)
  return signOutCallback
}
