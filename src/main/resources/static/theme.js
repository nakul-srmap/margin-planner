// Runs before the page paints, so the chosen theme never flashes.
try {
  var t = localStorage.getItem("theme");
  if (!t) t = matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  document.documentElement.dataset.theme = t;
} catch (e) { /* storage blocked: follow the light default */ }
