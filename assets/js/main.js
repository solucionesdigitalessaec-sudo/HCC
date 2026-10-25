/* HCC — Colegio en casa · Scripts del sitio */

(function () {
  "use strict";

  // ---------- Menú móvil ----------
  var toggle = document.querySelector(".nav-toggle");
  var nav = document.getElementById("main-nav");

  if (toggle && nav) {
    toggle.addEventListener("click", function () {
      var open = nav.classList.toggle("is-open");
      toggle.setAttribute("aria-expanded", String(open));
    });

    nav.addEventListener("click", function (e) {
      if (e.target.tagName === "A") {
        nav.classList.remove("is-open");
        toggle.setAttribute("aria-expanded", "false");
      }
    });
  }

  // ---------- Año del pie de página ----------
  document.querySelectorAll("[data-year]").forEach(function (el) {
    el.textContent = new Date().getFullYear();
  });

  // ---------- Formularios ----------
  // Cada formulario con [data-form] se valida en el navegador y se envía con
  // fetch al endpoint indicado en su atributo "action" (por ejemplo Formspree).
  // Si action es "#", el envío se simula para poder probar el sitio sin backend.

  var MESSAGES = {
    valueMissing: "Este campo es obligatorio.",
    typeMismatch: "Revisa el formato de este dato.",
    patternMismatch: "Revisa el formato de este dato.",
    tooShort: "El texto es demasiado corto.",
    rangeUnderflow: "El valor es demasiado bajo.",
    rangeOverflow: "El valor es demasiado alto."
  };

  function errorFor(input) {
    var v = input.validity;
    for (var key in MESSAGES) {
      if (v[key]) return input.dataset.error || MESSAGES[key];
    }
    return "";
  }

  function validateField(input) {
    var field = input.closest(".field");
    if (!field) return input.checkValidity();
    var msg = errorFor(input);
    var box = field.querySelector(".error-msg");
    field.classList.toggle("has-error", Boolean(msg));
    if (box) box.textContent = msg;
    input.setAttribute("aria-invalid", msg ? "true" : "false");
    return !msg;
  }

  document.querySelectorAll("form[data-form]").forEach(function (form) {
    var status = form.querySelector(".form-status");
    var submit = form.querySelector('button[type="submit"]');
    var inputs = form.querySelectorAll("input, select, textarea");

    inputs.forEach(function (input) {
      input.addEventListener("blur", function () { validateField(input); });
      input.addEventListener("input", function () {
        if (input.closest(".field.has-error")) validateField(input);
      });
    });

    form.addEventListener("submit", function (e) {
      e.preventDefault();
      status.className = "form-status";

      var firstInvalid = null;
      inputs.forEach(function (input) {
        if (!validateField(input) && !firstInvalid) firstInvalid = input;
      });

      if (firstInvalid) {
        firstInvalid.focus();
        status.textContent = "Por favor revisa los campos marcados.";
        status.classList.add("error");
        return;
      }

      // Campo trampa anti-spam: si un bot lo llena, no enviamos nada.
      var hp = form.querySelector(".hp-field input");
      if (hp && hp.value) return;

      var successMsg = form.dataset.success || "¡Gracias! Hemos recibido tu mensaje.";
      var original = submit.textContent;
      submit.disabled = true;
      submit.textContent = "Enviando…";

      var action = form.getAttribute("action");
      var request = action && action !== "#"
        ? fetch(action, {
            method: "POST",
            body: new FormData(form),
            headers: { Accept: "application/json" }
          }).then(function (res) {
            if (!res.ok) throw new Error("HTTP " + res.status);
          })
        : new Promise(function (resolve) {
            console.info("[HCC] Formulario sin endpoint configurado. Datos:",
              Object.fromEntries(new FormData(form)));
            setTimeout(resolve, 600);
          });

      request
        .then(function () {
          form.reset();
          status.textContent = successMsg;
          status.classList.add("success");
        })
        .catch(function () {
          status.textContent =
            "No pudimos enviar el formulario. Intenta de nuevo o escríbenos directamente por correo.";
          status.classList.add("error");
        })
        .finally(function () {
          submit.disabled = false;
          submit.textContent = original;
          status.scrollIntoView({ behavior: "smooth", block: "center" });
        });
    });
  });
})();
