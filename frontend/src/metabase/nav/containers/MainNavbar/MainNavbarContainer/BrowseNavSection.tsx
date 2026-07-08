import { c, t } from "ttag";

import { CollapseSection } from "metabase/common/components/CollapseSection";
import { useUserSetting } from "metabase/common/hooks";
import { ActionIcon, Icon, Tooltip } from "metabase/ui";

import { PaddedSidebarLink, SidebarHeading } from "../MainNavbar.styled";
import { trackAddDataModalOpened } from "../analytics";
import type { SelectedItem } from "../types";

import { useAddDataPermissions } from "./AddDataModal/use-add-data-permission";

export const BrowseNavSection = ({
  nonEntityItem,
  onItemSelect,
  onAddDataModalOpen,
  hasDataAccess,
}: {
  nonEntityItem: SelectedItem;
  onItemSelect: () => void;
  onAddDataModalOpen: () => void;
  hasDataAccess: boolean;
}) => {
  const BROWSE_MODELS_URL = "/browse/models";
  const BROWSE_DATA_URL = "/browse/databases";
  const BROWSE_METRICS_URL = "/browse/metrics";

  const [expandBrowse = true, setExpandBrowse] = useUserSetting(
    "expand-browse-in-nav",
  );

  const { canPerformMeaningfulActions } = useAddDataPermissions();

  // ibykus: im eingebetteten iframe die Browse-/Add-Data-Einstiege NICHT verstecken —
  // Erstellen/Navigieren soll wie in einer nicht-eingebetteten Instanz möglich sein.
  const showAddDataButton = canPerformMeaningfulActions;

  return (
    <CollapseSection
      header={
        <SidebarHeading>
          {c("A noun, shown in the sidebar as a navigation link").t`Data`}
        </SidebarHeading>
      }
      initialState={expandBrowse ? "expanded" : "collapsed"}
      iconPosition="right"
      iconSize={8}
      onToggle={setExpandBrowse}
      rightAction={
        showAddDataButton ? (
          <Tooltip label={t`Add data`}>
            <ActionIcon
              aria-label={t`Add data`}
              color="text-secondary"
              onClick={() => {
                trackAddDataModalOpened("left-nav");
                onAddDataModalOpen();
              }}
            >
              <Icon name="add" />
            </ActionIcon>
          </Tooltip>
        ) : undefined
      }
      role="section"
      aria-label={t`Data`}
    >
      {hasDataAccess && (
        <PaddedSidebarLink
          icon="database"
          url={BROWSE_DATA_URL}
          isSelected={nonEntityItem?.url?.startsWith(BROWSE_DATA_URL)}
          onClick={onItemSelect}
          aria-label={t`Browse databases`}
        >
          {t`Databases`}
        </PaddedSidebarLink>
      )}

      <PaddedSidebarLink
        icon="model"
        url={BROWSE_MODELS_URL}
        isSelected={nonEntityItem?.url?.startsWith(BROWSE_MODELS_URL)}
        onClick={onItemSelect}
        aria-label={t`Browse models`}
      >
        {t`Models`}
      </PaddedSidebarLink>

      <PaddedSidebarLink
        icon="metric"
        url={BROWSE_METRICS_URL}
        isSelected={nonEntityItem?.url?.startsWith(BROWSE_METRICS_URL)}
        onClick={onItemSelect}
        aria-label={t`Browse metrics`}
      >
        {t`Metrics`}
      </PaddedSidebarLink>
    </CollapseSection>
  );
};
