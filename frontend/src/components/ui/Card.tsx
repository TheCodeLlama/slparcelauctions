import type { HTMLAttributes } from "react";
import { cn } from "@/lib/cn";

type CardProps = HTMLAttributes<HTMLDivElement>;

function CardRoot({ className, children, ...rest }: CardProps) {
  return (
    <div
      className={cn(
        "bg-surface-container-lowest rounded-default shadow-soft overflow-hidden",
        className
      )}
      {...rest}
    >
      {children}
    </div>
  );
}

function CardHeader({ className, children, ...rest }: CardProps) {
  return (
    <div className={cn("p-6 pb-4", className)} {...rest}>
      {children}
    </div>
  );
}

function CardBody({ className, children, ...rest }: CardProps) {
  return (
    <div className={cn("px-6 py-4", className)} {...rest}>
      {children}
    </div>
  );
}

function CardFooter({ className, children, ...rest }: CardProps) {
  return (
    <div className={cn("p-6 pt-4", className)} {...rest}>
      {children}
    </div>
  );
}

type CardComponent = typeof CardRoot & {
  Header: typeof CardHeader;
  Body: typeof CardBody;
  Footer: typeof CardFooter;
};

export const Card = CardRoot as CardComponent;
Card.Header = CardHeader;
Card.Body = CardBody;
Card.Footer = CardFooter;

export { CardHeader, CardBody, CardFooter };
