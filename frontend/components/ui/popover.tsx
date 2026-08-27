import * as React from "react"
import * as PopoverPrimitive from "@radix-ui/react-popover"
import { cn } from "@/lib/utils"

const Popover = PopoverPrimitive.Root

const PopoverTrigger = PopoverPrimitive.Trigger

const PopoverContent = React.forwardRef<
  React.ElementRef<typeof PopoverPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof PopoverPrimitive.Content>
>(({ className, align = "center", sideOffset = 4, ...props }, ref) => (
  <PopoverPrimitive.Portal>
    <PopoverPrimitive.Content
      ref={ref}
      align={align}
      sideOffset={sideOffset}
      className={cn(
        // `bg-theme-primary` / `text-theme-primary`, NOT shadcn's stock
        // `bg-popover` / `text-popover-foreground`: this app defines no
        // `--popover` token, so those resolve to nothing and the popover renders
        // with no background at all, the page showing straight through it. Every
        // call site in the app already overrides the background for exactly that
        // reason; this default just means the next one does not have to know.
        "z-50 w-72 rounded-xl border bg-theme-primary p-4 text-theme-primary shadow-[0_8px_24px_rgba(0,0,0,0.12)] outline-none",
        className
      )}
      style={{ animation: 'none', transition: 'none' }}
      {...props}
    />
  </PopoverPrimitive.Portal>
))
PopoverContent.displayName = PopoverPrimitive.Content.displayName

export { Popover, PopoverTrigger, PopoverContent }

