import type { Metadata } from "next";
import "./globals.css";
import { FeedbackChat } from '@automate/feedback-lib/FeedbackChat';

export const metadata: Metadata = {
  title: "wakeUp",
  description: "Alarm clock that is louder and harder to dismiss than the stock one: alarm-stream audio with gain beyond max, escalation, vibration + flash, and a dismiss that requires proving you are awake.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body>{children}
        <FeedbackChat issuesPath="/feedback-lib-issues" />
</body>
    </html>
  );
}
