import { createMockState } from "metabase/redux/store/mocks";
import { createMockUser } from "metabase-types/api/mocks";

import { canAccessDataStudio } from "./selectors";

describe("canAccessDataStudio", () => {
  it("returns true when user is admin", () => {
    const state = createMockState({
      currentUser: createMockUser({
        is_superuser: true,
        is_data_analyst: false,
      }),
    });

    expect(canAccessDataStudio(state)).toBe(true);
  });

  it("returns true when user is analyst", () => {
    const state = createMockState({
      currentUser: createMockUser({
        is_superuser: false,
        is_data_analyst: true,
      }),
    });

    expect(canAccessDataStudio(state)).toBe(true);
  });

  it("returns false when user is neither admin nor analyst", () => {
    const state = createMockState({
      currentUser: createMockUser({
        is_superuser: false,
        is_data_analyst: false,
      }),
    });

    expect(canAccessDataStudio(state)).toBe(false);
  });
});
