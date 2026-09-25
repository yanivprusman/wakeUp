import type { Metadata } from "next";
import "./globals.css";
import FeedbackChatMount from "./FeedbackChatMount";

export const metadata: Metadata = {
  title: "wakeUp",
  description: "Alarm clock that is louder and harder to dismiss than the stock one: alarm-stream audio with gain beyond max, escalation, vibration + flash, and a dismiss that requires proving you are awake.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body>{children}
        <FeedbackChatMount />
</body>
    </html>
  );
}
