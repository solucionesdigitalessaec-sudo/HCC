# HCC · Colegio en casa — Sitio web (Fase 1)

Sitio informativo con proceso de admisiones para un colegio en casa (homeschool) en línea.
Está hecho con HTML, CSS y JavaScript puros: no requiere instalación ni compilación.

## Páginas

| Archivo           | Contenido                                                              |
| ----------------- | ---------------------------------------------------------------------- |
| `index.html`      | Inicio: propuesta de valor, beneficios, programas, pasos, testimonios  |
| `nosotros.html`   | Historia, misión, visión, valores y equipo                             |
| `programas.html`  | Preescolar, primaria y secundaria                                      |
| `admisiones.html` | Proceso, requisitos, **formulario de admisión** y preguntas frecuentes |
| `contacto.html`   | Canales de atención y formulario de contacto                           |

## Ver el sitio en tu computadora

Abre `index.html` en el navegador, o levanta un servidor local:

```bash
python3 -m http.server 8000
# luego abre http://localhost:8000
```

## Personalización pendiente

- **Colores y tipografías:** variables al inicio de `assets/css/styles.css` (`:root`).
- **Datos de contacto:** el correo `admisiones@tucolegio.com` y el WhatsApp `+00 000 000 000`
  aparecen en el pie de página de cada página y en `contacto.html`.
- **Textos de ejemplo:** los marcados en cursiva gris (testimonios, equipo, historia,
  requisitos legales, validez oficial en las preguntas frecuentes, cifras de programas).
- **Logo:** el bloque `.logo` del encabezado y `assets/img/favicon.svg`.

> El encabezado y el pie de página se repiten en cada archivo HTML.
> Si cambias un enlace o dato ahí, cámbialo en las 5 páginas.

## Recibir los formularios por correo

Los formularios de admisión y contacto validan los datos en el navegador y se envían con
`fetch`. Mientras su `action` sea `#`, el envío solo se **simula** (los datos se muestran en la consola).

Para recibirlos de verdad, por ejemplo con [Formspree](https://formspree.io) (plan gratuito):

1. Crea una cuenta y un formulario nuevo.
2. Copia la URL que te dan (p. ej. `https://formspree.io/f/abcdwxyz`).
3. Reemplaza `action="#"` por esa URL en `admisiones.html` y `contacto.html`.

Los campos ocultos `_subject` (asunto del correo) y `_gotcha` (trampa anti-spam) ya son compatibles con Formspree.

## Publicar

- **GitHub Pages:** Settings → Pages → Deploy from branch → rama principal, carpeta `/ (root)`.
- **Netlify / Vercel:** importa el repositorio; no hace falta comando de build.
