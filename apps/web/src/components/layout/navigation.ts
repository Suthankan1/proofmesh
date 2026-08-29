export type NavigationItemDefinition = {
  label: string;
  href: string;
};

export type NavigationGroupDefinition = {
  label: string;
  items: readonly NavigationItemDefinition[];
};

export const navigationGroups = [
  {
    label: "Overview",
    items: [
      {
        label: "Dashboard",
        href: "/dashboard",
      },
    ],
  },

  {
    label: "Operations",
    items: [
      {
        label: "Governed Actions",
        href: "/actions",
      },
      {
        label: "Approvals",
        href: "/approvals",
      },
      {
        label: "Incidents",
        href: "/incidents",
      },
    ],
  },

  {
    label: "Governance",
    items: [
      {
        label: "Agents",
        href: "/agents",
      },
      {
        label: "Tools",
        href: "/tools",
      },
      {
        label: "Policies",
        href: "/policies",
      },
    ],
  },

  {
    label: "Assurance",
    items: [
      {
        label: "Evidence",
        href: "/evidence",
      },
    ],
  },

  {
    label: "Administration",
    items: [
      {
        label: "Organization",
        href: "/organization",
      },
    ],
  },
] as const satisfies readonly NavigationGroupDefinition[];