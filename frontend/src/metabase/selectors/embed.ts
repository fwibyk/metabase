import type {
  InteractiveEmbeddingOptionsState,
  State,
} from "metabase/redux/store";
import { getUser } from "metabase/selectors/user";
import { isWithinIframe } from "metabase/utils/iframe";

export const getIsEmbeddingIframe = (_state?: State): boolean => {
  return isWithinIframe();
};

type EmptyObject = Record<string, never>;
export const getEmbedOptions = (
  state: State,
): InteractiveEmbeddingOptionsState | EmptyObject => {
  // ibykus: role-based UI when embedded via our custom SSO — Full-App-Embedding, das
  // sich wie eine nicht-eingebettete Instanz verhält (Erstellen/Bearbeiten erlaubt).
  // header/additional_info/action_buttons schalten Frage-/Dashboard-Kopfzeile und
  // Bearbeiten/Speichern-Buttons frei (sonst im iframe per Default aus).
  if (isWithinIframe()) {
    return {
      top_nav: true,
      side_nav: true,
      search: true,
      new_button: true,
      breadcrumbs: true,
      logo: true,
      header: true,
      additional_info: true,
      action_buttons: true,
    } as InteractiveEmbeddingOptionsState;
  }
  return state.embed.options;
};
