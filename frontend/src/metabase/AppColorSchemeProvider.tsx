import {
  type ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useState,
} from "react";
import { useMedia } from "react-use";

import { isEmbeddingSdk } from "metabase/embedding-sdk/config";
import {
  ColorSchemeContext,
  type ColorSchemeContextType,
  colorSchemeContextDefaultValue,
} from "metabase/ui/components/theme/ColorSchemeProvider";
import { isWithinIframe } from "metabase/utils/iframe";

import type { ColorScheme } from "./utils/color-scheme";

interface AppColorSchemeProviderProps {
  children: ReactNode;
  defaultColorScheme?: ColorScheme;
  forceColorScheme?: "light" | "dark" | null;
  onUpdateColorScheme?: (scheme: ColorScheme) => void;
}

const getNextScheme = (scheme: "light" | "dark") =>
  scheme === "dark" ? "light" : "dark";

// Vom Einbettungs-Host (Analytics Studio) via /auth/sso-local gesetztes Cookie.
// Erlaubt es, das eingebettete Metabase zentral hell/dunkel zu schalten, ohne die
// persoenliche color-scheme-Praeferenz des Nutzers zu ueberschreiben.
const getEmbedColorSchemeCookie = (): "light" | "dark" | null => {
  if (typeof document === "undefined") {
    return null;
  }
  const match = document.cookie.match(
    /(?:^|;\s*)mb-embed-color-scheme=(light|dark)\b/,
  );
  return match ? (match[1] as "light" | "dark") : null;
};

export function AppColorSchemeProvider({
  children,
  defaultColorScheme = "auto",
  forceColorScheme,
  onUpdateColorScheme,
}: AppColorSchemeProviderProps) {
  const systemColorScheme = useMedia("(prefers-color-scheme: dark)")
    ? "dark"
    : "light";

  const [colorScheme, setColorScheme] = useState<ColorScheme>(
    defaultColorScheme || "auto",
  );

  useEffect(() => {
    // NOTE: The `defaultColorScheme` prop may change in cases where the
    // page hasn't reloaded (therefore embedded user preferences haven't
    // changed) but a new set of preferences arrives from events, such as
    // session changes after login/logout.
    //
    // If such new preferences specify a different color scheme, we then
    // react to those changes.
    setColorScheme(defaultColorScheme);
  }, [defaultColorScheme]);

  const resolvedColorScheme = useMemo(() => {
    if (forceColorScheme) {
      return forceColorScheme;
    }
    if (isWithinIframe()) {
      // Vom Host gesetztes Cookie respektieren; sonst wie bisher hell.
      return getEmbedColorSchemeCookie() ?? "light";
    }
    return colorScheme === "auto" ? systemColorScheme : colorScheme;
  }, [colorScheme, forceColorScheme, systemColorScheme]);

  const handleColorSchemeUpdate = useCallback(
    (value: ColorScheme) => {
      setColorScheme(value);
      onUpdateColorScheme?.(value);
    },
    [onUpdateColorScheme],
  );

  const value: ColorSchemeContextType = isEmbeddingSdk()
    ? colorSchemeContextDefaultValue
    : {
        colorScheme,
        resolvedColorScheme,
        setColorScheme: handleColorSchemeUpdate,
        systemColorScheme,
        toggleColorScheme: () => {
          const nextScheme = getNextScheme(resolvedColorScheme);
          handleColorSchemeUpdate(
            nextScheme === systemColorScheme ? "auto" : nextScheme,
          );
        },
      };

  return (
    <ColorSchemeContext.Provider value={value}>
      {children}
    </ColorSchemeContext.Provider>
  );
}
