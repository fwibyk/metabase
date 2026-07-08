import { PLUGIN_REMOTE_SYNC } from "metabase/plugins";
import type { State } from "metabase/redux/store";
import { getUserIsAdmin, getUserIsAnalyst } from "metabase/selectors/user";

// Must be in sync with CanAccessDataStudio in frontend/src/metabase/route-guards.tsx
// ibykus: Data Studio auch im eingebetteten iframe zugänglich (Instanz ist immer
// eingebettet) — bleibt rollen-gated (Admin/Analyst).
export function canAccessDataStudio(state: State) {
  return getUserIsAdmin(state) || getUserIsAnalyst(state);
}

export const getUserCanWriteSegments = (
  state: State,
  isTablePublished: boolean,
) => {
  const isAdmin = getUserIsAdmin(state);

  if (!isAdmin) {
    return false;
  }

  const isRemoteSyncReadOnly =
    PLUGIN_REMOTE_SYNC.getIsRemoteSyncReadOnly(state);

  return !isRemoteSyncReadOnly || !isTablePublished;
};

export const getUserCanWriteMeasures = (
  state: State,
  isTablePublished: boolean,
) => {
  const isAdmin = getUserIsAdmin(state);

  if (!isAdmin) {
    return false;
  }

  const isRemoteSyncReadOnly =
    PLUGIN_REMOTE_SYNC.getIsRemoteSyncReadOnly(state);

  return !isRemoteSyncReadOnly || !isTablePublished;
};
