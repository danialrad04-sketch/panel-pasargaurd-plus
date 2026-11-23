"use strict";

const password = document.getElementById("login-password");
const reveal = document.getElementById("reveal-password");
reveal.hidden = false;
reveal.addEventListener("click", () => {
  const visible = password.type === "password";
  password.type = visible ? "text" : "password";
  reveal.setAttribute("aria-pressed", String(visible));
  reveal.textContent = visible ? "پنهان کردن رمز" : "نمایش رمز";
});
