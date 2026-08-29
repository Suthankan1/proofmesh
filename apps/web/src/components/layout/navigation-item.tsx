"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { cn } from "@/lib/styles/cn";

type NavigationItemProps = {
  href: string;
  label: string;
};

export function NavigationItem({
  href,
  label,
}: NavigationItemProps) {
  const pathname = usePathname();

  const isActive =
    href === "/dashboard"
      ? pathname === href
      : pathname === href || pathname.startsWith(`${href}/`);

  return (
    <Link
      href={href}
      aria-current={isActive ? "page" : undefined}
      className={cn(
        "flex min-h-9 items-center rounded-md px-3 text-sm font-medium",
        "transition-colors duration-150",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2",
        "focus-visible:ring-offset-surface",
        isActive
          ? "bg-brand-subtle text-brand-foreground"
          : "text-foreground-secondary hover:bg-surface-subtle hover:text-foreground",
      )}
    >
      {label}
    </Link>
  );
}