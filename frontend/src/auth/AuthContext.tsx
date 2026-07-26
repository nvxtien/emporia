import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import type { User } from 'oidc-client-ts'
import { AuthContext, type AuthContextValue } from './auth-context'
import {
  completeSignIn as processSignInCallback,
  completeSignOut as processSignOutCallback,
  userManager,
} from './oidc'

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Authentication failed. Please try again.'
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true

    const onUserLoaded = (loadedUser: User) => {
      if (active) {
        setUser(loadedUser.expired ? null : loadedUser)
        setIsLoading(false)
      }
    }
    const onUserUnloaded = () => {
      if (active) {
        setUser(null)
      }
    }
    const onAccessTokenExpired = () => {
      if (active) {
        setUser(null)
        setError('Your session expired. Sign in again to continue.')
      }
      void userManager.removeUser()
    }

    userManager.events.addUserLoaded(onUserLoaded)
    userManager.events.addUserUnloaded(onUserUnloaded)
    userManager.events.addAccessTokenExpired(onAccessTokenExpired)

    void userManager
      .clearStaleState()
      .then(() => userManager.getUser())
      .then((storedUser) => {
        if (!active) return
        if (storedUser?.expired) {
          void userManager.removeUser()
          setUser(null)
        } else {
          setUser(storedUser)
        }
      })
      .catch((loadError: unknown) => {
        if (active) setError(errorMessage(loadError))
      })
      .finally(() => {
        if (active) setIsLoading(false)
      })

    return () => {
      active = false
      userManager.events.removeUserLoaded(onUserLoaded)
      userManager.events.removeUserUnloaded(onUserUnloaded)
      userManager.events.removeAccessTokenExpired(onAccessTokenExpired)
    }
  }, [])

  const login = useCallback(async () => {
    setError(null)
    setIsLoading(true)
    try {
      await userManager.signinRedirect()
    } catch (loginError: unknown) {
      setError(errorMessage(loginError))
      setIsLoading(false)
    }
  }, [])

  const logout = useCallback(async () => {
    setError(null)
    setIsLoading(true)
    try {
      await userManager.signoutRedirect()
    } catch (logoutError: unknown) {
      setError(errorMessage(logoutError))
      setIsLoading(false)
    }
  }, [])

  const completeSignIn = useCallback(async () => {
    setError(null)
    setIsLoading(true)
    try {
      const signedInUser = await processSignInCallback()
      setUser(signedInUser)
    } catch (callbackError: unknown) {
      setError(errorMessage(callbackError))
      throw callbackError
    } finally {
      setIsLoading(false)
    }
  }, [])

  const completeSignOut = useCallback(async () => {
    setError(null)
    setIsLoading(true)
    try {
      await processSignOutCallback()
      await userManager.removeUser()
      setUser(null)
    } catch (callbackError: unknown) {
      setError(errorMessage(callbackError))
      throw callbackError
    } finally {
      setIsLoading(false)
    }
  }, [])

  const clearError = useCallback(() => setError(null), [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated: Boolean(user && !user.expired),
      isLoading,
      error,
      login,
      logout,
      completeSignIn,
      completeSignOut,
      clearError,
    }),
    [user, isLoading, error, login, logout, completeSignIn, completeSignOut, clearError],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
