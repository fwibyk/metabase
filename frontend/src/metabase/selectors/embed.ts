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
  // ibykus: role-based UI when embedded via our custom SSO
  if (isWithinIframe()) {
    return {
      top_nav: true,
      side_nav: true,
      search: true,
      new_button: true,
      breadcrumbs: true,
      logo: true,
    } as InteractiveEmbeddingOptionsState;
  }
  return state.embed.options;
};
