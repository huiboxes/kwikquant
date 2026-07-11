import {
  CircleCheckIcon,
  InfoIcon,
  Loader2Icon,
  OctagonXIcon,
  TriangleAlertIcon,
} from "lucide-react"
import { useThemeStore } from "@/stores/themeStore"
import { Toaster as Sonner, type ToasterProps } from "sonner"

const Toaster = ({ ...props }: ToasterProps) => {
  const colorScheme = useThemeStore((s) => s.colorScheme)

  return (
    <Sonner
      theme={colorScheme}
      className="toaster group"
      icons={{
        success: <CircleCheckIcon className="size-4" />,
        info: <InfoIcon className="size-4" />,
        warning: <TriangleAlertIcon className="size-4" />,
        error: <OctagonXIcon className="size-4" />,
        loading: <Loader2Icon className="size-4 animate-spin" />,
      }}
      toastOptions={{
        // 照原型 .kq-toast-item:左边 3px accent 边 + shadow-pop + min-w-280
        classNames: { toast: 'border-l-[3px] border-l-accent shadow-pop min-w-[280px]' },
      }}
      style={
        {
          "--normal-bg": "var(--surface-card)",
          "--normal-text": "var(--text-primary)",
          "--normal-border": "transparent",
          "--border-radius": "var(--radius-md)",
        } as React.CSSProperties
      }
      {...props}
    />
  )
}

export { Toaster }
