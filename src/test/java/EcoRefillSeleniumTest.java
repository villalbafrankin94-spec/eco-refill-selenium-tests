import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.*;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.Keys;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import org.openqa.selenium.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

public class EcoRefillSeleniumTest {

    private static final String BASE_URL = "https://eco-refill-31771.web.app";

    private static final String USER_EMAIL = "cristian3@gmail.com";
    private static final String USER_PASSWORD = "123456";

    private static final String JEFE_EMAIL = "villalba@gmail.com";
    private static final String JEFE_PASSWORD = "1234567";

    private static final Duration PAUSA_SYNC_FLUTTER = Duration.ofMillis(350);

    private static final int MAX_INTENTOS_ESCRIBIR = 3;

    private WebDriver driver;
    private WebDriverWait wait;

    @BeforeAll
    static void setupClass() {
        WebDriverManager.chromedriver().setup();
    }

    @BeforeEach
    void setup() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--window-size=1280,900");
        // options.addArguments("--headless=new");

        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    @AfterEach
    void teardown() {
        if (driver != null) {
            driver.quit();
        }
    }

    private void capturarEvidencia(String nombreBase) {
        try {
            Path carpeta = Paths.get("target");
            Files.createDirectories(carpeta);

            byte[] captura = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            Files.write(carpeta.resolve(nombreBase + ".png"), captura);

            String html = driver.getPageSource();
            Files.write(carpeta.resolve(nombreBase + ".html"), html.getBytes(StandardCharsets.UTF_8));

            System.out.println(">>> Evidencia de fallo guardada en: "
                    + carpeta.resolve(nombreBase + ".png") + " y "
                    + carpeta.resolve(nombreBase + ".html"));
        } catch (IOException e) {
            System.err.println("No se pudo guardar la evidencia de fallo: " + e.getMessage());
        }
    }

    private boolean intentarEscribirEnCampo(String ariaLabel, String texto) {
        boolean exitoso = false;
        WebElement campoUsado = null;

        try {
            List<WebElement> nodosSemanticos = driver.findElements(
                    By.xpath("//flt-semantics[@aria-label='" + ariaLabel + "' and @role='textbox']"));

            if (nodosSemanticos.isEmpty()) {
                System.out.println(">>> DEBUG escribirEnCampo: no se encontró flt-semantics[role='textbox'] "
                        + "para aria-label='" + ariaLabel + "', se intentará método alternativo");
            } else {
                WebElement nodoSemantico = nodosSemanticos.get(0);
                new Actions(driver).moveToElement(nodoSemantico).click().perform();

                new Actions(driver)
                        .sendKeys(Keys.chord(Keys.CONTROL, "a"))
                        .sendKeys(Keys.DELETE)
                        .sendKeys(texto)
                        .perform();

                exitoso = true;
                System.out.println(">>> DEBUG escribirEnCampo: click en flt-semantics[role='textbox'] + sendKeys real OK");
            }
        } catch (Exception e) {
            System.out.println(">>> DEBUG escribirEnCampo: método principal (flt-semantics) FALLÓ ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }

        if (!exitoso) {
            try {
                WebElement campo = buscarInputFresco(ariaLabel);
                new Actions(driver).moveToElement(campo).click().perform();
                campo.clear();
                campo.sendKeys(texto);
                exitoso = true;
                campoUsado = campo;
                System.out.println(">>> DEBUG escribirEnCampo: fallback 1 (click+sendKeys sobre input) OK");
            } catch (Exception e) {
                System.out.println(">>> DEBUG escribirEnCampo: fallback 1 FALLÓ ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
        }

        if (!exitoso) {
            try {
                WebElement campo = buscarInputFresco(ariaLabel);
                ((JavascriptExecutor) driver).executeScript("arguments[0].focus();", campo);
                new Actions(driver)
                        .sendKeys(Keys.chord(Keys.CONTROL, "a"))
                        .sendKeys(Keys.DELETE)
                        .sendKeys(texto)
                        .perform();
                exitoso = true;
                campoUsado = campo;
                System.out.println(">>> DEBUG escribirEnCampo: fallback 2 (foco por JS + sendKeys real) OK");
            } catch (Exception e2) {
                System.out.println(">>> DEBUG escribirEnCampo: fallback 2 TAMBIÉN falló ("
                        + e2.getClass().getSimpleName() + ": " + e2.getMessage()
                        + "), usando último recurso (set value por JS -- puede NO sincronizar con Flutter)");
            }
        }

        if (!exitoso) {
            try {
                WebElement campo = buscarInputFresco(ariaLabel);
                JavascriptExecutor js = (JavascriptExecutor) driver;
                js.executeScript(
                        "var el = arguments[0];" +
                                "var valor = arguments[1];" +
                                "el.focus();" +
                                "var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;" +
                                "var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;" +
                                "setter.call(el, valor);" +
                                "el.dispatchEvent(new Event('input', { bubbles: true }));" +
                                "el.dispatchEvent(new Event('change', { bubbles: true }));",
                        campo, texto);
                campoUsado = campo;
            } catch (Exception e3) {
                System.out.println(">>> DEBUG escribirEnCampo: último recurso también falló (" + e3.getMessage() + ")");
            }
        }

        try {
            Thread.sleep(PAUSA_SYNC_FLUTTER.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        try {
            String valorFinal;
            boolean verificadoConReferenciaDirecta = false;

            if (campoUsado != null) {
                try {
                    valorFinal = campoUsado.getAttribute("value");
                    verificadoConReferenciaDirecta = true;
                } catch (Exception eStale) {
                    System.out.println(">>> DEBUG escribirEnCampo: la referencia usada para escribir en '"
                            + ariaLabel + "' quedó obsoleta (" + eStale.getClass().getSimpleName()
                            + "), se intentará verificar re-buscando por aria-label");
                    valorFinal = null;
                }
            } else {
                valorFinal = null;
            }

            if (!verificadoConReferenciaDirecta) {
                WebElement campoFinal = buscarInputFresco(ariaLabel);
                valorFinal = campoFinal.getAttribute("value");
            }

            if (!texto.equals(valorFinal)) {
                System.out.println(">>> DEBUG escribirEnCampo: ADVERTENCIA - el valor final de '"
                        + ariaLabel + "' no coincide con lo esperado. esperado=[" + texto
                        + "] real=[" + valorFinal + "]");
                return false;
            } else {
                System.out.println(">>> DEBUG escribirEnCampo: valor verificado OK para '" + ariaLabel + "'"
                        + (verificadoConReferenciaDirecta ? " (referencia directa)" : " (re-búsqueda por aria-label)"));
                return true;
            }
        } catch (Exception e4) {
            System.out.println(">>> DEBUG escribirEnCampo: no se pudo verificar el valor final de '"
                    + ariaLabel + "' (" + e4.getMessage() + ")");
            return false;
        }
    }

    private void escribirEnCampo(String ariaLabel, String texto) {
        for (int intento = 1; intento <= MAX_INTENTOS_ESCRIBIR; intento++) {
            if (intentarEscribirEnCampo(ariaLabel, texto)) {
                return;
            }
            System.out.println(">>> DEBUG escribirEnCampo: intento " + intento + "/" + MAX_INTENTOS_ESCRIBIR
                    + " falló para '" + ariaLabel + "', reintentando...");
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        System.out.println(">>> DEBUG escribirEnCampo: ADVERTENCIA FINAL - no se pudo escribir '"
                + texto + "' en '" + ariaLabel + "' tras " + MAX_INTENTOS_ESCRIBIR + " intentos");
    }

    private void esperarFlutterMontado() {
        long limite = System.currentTimeMillis() + 30_000;

        while (System.currentTimeMillis() < limite) {
            if (!driver.findElements(By.tagName("flt-glass-pane")).isEmpty()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        System.out.println(">>> DEBUG esperarFlutterMontado: no se detectó <flt-glass-pane> en 15s; "
                + "Flutter puede no haber montado todavía (o esta versión usa otro elemento raíz).");
    }

    private void enableFlutterAccessibility() {
        esperarFlutterMontado();

        long limite = System.currentTimeMillis() + 10_000;

        while (System.currentTimeMillis() < limite) {
            List<WebElement> placeholders = driver.findElements(
                    By.cssSelector("flt-semantics-placeholder"));

            if (placeholders.isEmpty()) {
                return;
            }

            try {
                new Actions(driver).moveToElement(placeholders.get(0)).click().perform();
            } catch (Exception ignorado) {
            }

            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }

        }
    }

    private WebElement buscarInputFresco(String ariaLabel) {
        List<WebElement> candidatos = driver.findElements(By.xpath(
                "//input[@aria-label='" + ariaLabel + "'] | //textarea[@aria-label='" + ariaLabel + "']"));

        System.out.println(">>> DEBUG buscarInputFresco: candidatos para aria-label='"
                + ariaLabel + "': " + candidatos.size());

        WebElement mejor = null;
        long mejorArea = Long.MAX_VALUE;

        for (int i = 0; i < candidatos.size(); i++) {
            WebElement c = candidatos.get(i);
            try {
                boolean visible = c.isDisplayed();
                boolean habilitado = c.isEnabled();
                org.openqa.selenium.Rectangle r = c.getRect();
                long area = (long) r.getWidth() * (long) r.getHeight();

                System.out.println("    [" + i + "] displayed=" + visible
                        + " enabled=" + habilitado
                        + " x=" + r.getX() + " y=" + r.getY()
                        + " w=" + r.getWidth() + " h=" + r.getHeight()
                        + " area=" + area
                        + " value=[" + c.getAttribute("value") + "]");

                if (!visible || !habilitado || area <= 0) {
                    continue;
                }
                if (area < mejorArea) {
                    mejor = c;
                    mejorArea = area;
                }
            } catch (Exception e) {
                System.out.println("    [" + i + "] (elemento no accesible: " + e.getMessage() + ")");
            }
        }

        if (mejor == null) {
            if (candidatos.isEmpty()) {
                throw new NoSuchElementException(
                        "No se encontró ningún input/textarea con aria-label: " + ariaLabel);
            }
            System.out.println(">>> DEBUG buscarInputFresco: ningún candidato visible+habilitado+área>0 "
                    + "para '" + ariaLabel + "'; devolviendo el primero como último recurso "
                    + "(revisar el log de candidatos de arriba)");
            return candidatos.get(0);
        }

        return mejor;
    }

    private void esperarCampoHabilitado(String ariaLabel, int timeoutSegundos) {
        long limite = System.currentTimeMillis() + timeoutSegundos * 1000L;
        boolean habilitado = false;

        while (System.currentTimeMillis() < limite) {
            try {
                List<WebElement> candidatos = driver.findElements(By.xpath(
                        "//input[@aria-label='" + ariaLabel + "'] | //textarea[@aria-label='" + ariaLabel + "']"));
                for (WebElement c : candidatos) {
                    if (c.isDisplayed() && c.isEnabled()) {
                        habilitado = true;
                        break;
                    }
                }
            } catch (Exception ignorado) {
            }

            if (habilitado) {
                return;
            }

            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        System.out.println(">>> DEBUG esperarCampoHabilitado: el campo '" + ariaLabel
                + "' NUNCA se habilitó (enabled=true) en " + timeoutSegundos + "s. "
                + "Esto sugiere un bug en la app (el TextField de Flutter está deshabilitado "
                + "por una condición que no se cumple), no un problema de Selenium.");
    }

    private WebElement encontrarBotonPorTexto(String texto) {
        List<WebElement> todos = driver.findElements(
                By.xpath("//flt-semantics[contains(., '" + texto + "')]"));

        System.out.println(">>> DEBUG candidatos para botón '" + texto + "': " + todos.size());

        WebElement mejor = null;
        long mejorArea = Long.MAX_VALUE;

        for (int i = 0; i < todos.size(); i++) {
            WebElement c = todos.get(i);
            try {
                boolean visible = c.isDisplayed();
                org.openqa.selenium.Rectangle r = c.getRect();
                String role = c.getAttribute("role");
                long area = (long) r.getWidth() * (long) r.getHeight();

                System.out.println("    [" + i + "] displayed=" + visible
                        + " role=" + role
                        + " x=" + r.getX() + " y=" + r.getY()
                        + " w=" + r.getWidth() + " h=" + r.getHeight()
                        + " area=" + area
                        + " aria-label=" + c.getAttribute("aria-label"));

                if (!visible || area <= 0) {
                    continue;
                }

                boolean esBoton = "button".equals(role);
                boolean mejorEsBoton = mejor != null && "button".equals(mejor.getAttribute("role"));

                if (mejorEsBoton && !esBoton) {
                    continue;
                }
                if (esBoton && !mejorEsBoton) {
                    mejor = c;
                    mejorArea = area;
                    continue;
                }
                if (area < mejorArea) {
                    mejor = c;
                    mejorArea = area;
                }
            } catch (Exception e) {
                System.out.println("    [" + i + "] (elemento no accesible: " + e.getMessage() + ")");
            }
        }

        if (mejor == null) {
            if (todos.isEmpty()) {
                throw new NoSuchElementException("No se encontró ningún elemento con texto: " + texto);
            }
            return todos.get(0);
        }

        return mejor;
    }

    private WebElement esperarElementoConTexto(int timeoutSegundos, String... textosPosibles) {
        long limite = System.currentTimeMillis() + timeoutSegundos * 1000L;

        StringBuilder condicion = new StringBuilder();
        for (int i = 0; i < textosPosibles.length; i++) {
            if (i > 0) {
                condicion.append(" or ");
            }
            condicion.append("contains(., '").append(textosPosibles[i]).append("')");
        }
        String xpath = "//flt-semantics[" + condicion + "]";

        while (System.currentTimeMillis() < limite) {
            List<WebElement> candidatos = driver.findElements(By.xpath(xpath));

            WebElement mejor = null;
            long mejorArea = Long.MAX_VALUE;

            for (WebElement c : candidatos) {
                try {
                    if (!c.isDisplayed()) {
                        continue;
                    }
                    org.openqa.selenium.Rectangle r = c.getRect();
                    long area = (long) r.getWidth() * (long) r.getHeight();
                    if (area <= 0) {
                        continue;
                    }
                    if (area < mejorArea) {
                        mejor = c;
                        mejorArea = area;
                    }
                } catch (Exception ignorado) {
                }
            }

            if (mejor != null) {
                return mejor;
            }

            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        return null;
    }

    private int contarElementosConTexto(String texto) {
        List<WebElement> candidatos = driver.findElements(
                By.xpath("//flt-semantics[contains(., '" + texto + "')]"));

        int contador = 0;
        for (WebElement c : candidatos) {
            try {
                if (!c.isDisplayed()) {
                    continue;
                }
                org.openqa.selenium.Rectangle r = c.getRect();
                if ((long) r.getWidth() * (long) r.getHeight() > 0) {
                    contador++;
                }
            } catch (Exception ignorado) {
            }
        }
        return contador;
    }

    // Igual que esperarElementoConTexto(), pero para clickear botones reales:
    // prefiere fuertemente los candidatos con role='button' (como ya hace
    // encontrarBotonPorTexto()), en vez de quedarse con el de menor área
    // sin más. Esto evita clickear un <flt-semantics> de solo texto
    // (pointer-events: none) que por casualidad tenga menor área que el
    // botón real -- el click "no falla" pero tampoco hace nada.
    //
    // MODIFICADO: ahora imprime el mismo log de candidatos que
    // encontrarBotonPorTexto(), para poder confirmar en consola cuál
    // elemento fue elegido como "el botón" (ej. distinguir el título de
    // la tarjeta "Generar Notas" del botón morado real).
    private WebElement esperarBotonConTexto(int timeoutSegundos, String texto) {
        long limite = System.currentTimeMillis() + timeoutSegundos * 1000L;
        String xpath = "//flt-semantics[contains(., '" + texto + "')]";
        boolean primeraVuelta = true;

        while (System.currentTimeMillis() < limite) {
            List<WebElement> candidatos = driver.findElements(By.xpath(xpath));

            if (primeraVuelta) {
                System.out.println(">>> DEBUG esperarBotonConTexto: candidatos para '" + texto + "': "
                        + candidatos.size());
            }

            WebElement mejor = null;
            long mejorArea = Long.MAX_VALUE;

            for (int i = 0; i < candidatos.size(); i++) {
                WebElement c = candidatos.get(i);
                try {
                    boolean visible = c.isDisplayed();

                    if (!visible) {
                        if (primeraVuelta) {
                            System.out.println("    [" + i + "] displayed=false (descartado)");
                        }
                        continue;
                    }

                    org.openqa.selenium.Rectangle r = c.getRect();
                    long area = (long) r.getWidth() * (long) r.getHeight();
                    String role = c.getAttribute("role");

                    if (primeraVuelta) {
                        System.out.println("    [" + i + "] displayed=" + visible
                                + " role=" + role
                                + " x=" + r.getX() + " y=" + r.getY()
                                + " w=" + r.getWidth() + " h=" + r.getHeight()
                                + " area=" + area
                                + " aria-label=" + c.getAttribute("aria-label"));
                    }

                    if (area <= 0) {
                        continue;
                    }

                    boolean esBoton = "button".equals(role);
                    boolean mejorEsBoton = mejor != null && "button".equals(mejor.getAttribute("role"));

                    if (mejorEsBoton && !esBoton) {
                        continue;
                    }
                    if (esBoton && !mejorEsBoton) {
                        mejor = c;
                        mejorArea = area;
                        continue;
                    }
                    if (area < mejorArea) {
                        mejor = c;
                        mejorArea = area;
                    }
                } catch (Exception ignorado) {
                }
            }

            if (mejor != null) {
                if (primeraVuelta) {
                    try {
                        System.out.println(">>> DEBUG esperarBotonConTexto: elegido -> role="
                                + mejor.getAttribute("role") + " aria-label=" + mejor.getAttribute("aria-label"));
                    } catch (Exception ignorado) {
                    }
                }
                return mejor;
            }

            primeraVuelta = false;

            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        return null;
    }

    private void clickForzado(WebElement el) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block: 'center', inline: 'center'});", el);
            Thread.sleep(200);
        } catch (Exception eScroll) {
            System.out.println(">>> DEBUG clickForzado: no se pudo hacer scrollIntoView (" + eScroll.getMessage() + ")");
        }

        try {
            new Actions(driver).moveToElement(el).click().perform();
            return;
        } catch (Exception e) {
            System.out.println(">>> DEBUG clickForzado: click con Actions falló (" + e.getMessage() + "), probando click normal");
        }

        try {
            el.click();
        } catch (Exception e) {
            System.out.println(">>> DEBUG click normal falló (" + e.getMessage() + "), probando click por JS");
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
        }
    }

    private String detectarAvisoVisible() {
        List<WebElement> dialogos = driver.findElements(
                By.xpath("//flt-semantics[@role='alertdialog' or @role='dialog' or @role='alert']"));

        if (!dialogos.isEmpty()) {
            String texto = dialogos.get(0).getAttribute("aria-label");
            if (texto == null || texto.isBlank()) {
                texto = dialogos.get(0).getText();
            }
            return texto == null || texto.isBlank() ? "(diálogo sin texto legible)" : texto;
        }

        List<WebElement> porPalabra = driver.findElements(
                By.xpath("//*[not(self::input) and not(self::textarea)"
                        + " and not(starts-with(@aria-label,'Ingresa tu'))"
                        + " and (contains(@aria-label,'Aviso') or contains(@aria-label,'incorrect')"
                        + " or contains(@aria-label,'Error') or contains(@aria-label,'inválid')"
                        + " or contains(@aria-label,'invalid') or contains(@aria-label,'fallo')"
                        + " or contains(@aria-label,'no existe'))]"));
        if (!porPalabra.isEmpty()) {
            return porPalabra.get(0).getAttribute("aria-label");
        }

        return "(ninguno)";
    }

    private WebElement buscarInputFrescoSinFallar(String ariaLabel, int intentos) {
        for (int i = 0; i < intentos; i++) {
            try {
                return buscarInputFresco(ariaLabel);
            } catch (NoSuchElementException e) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        System.out.println(">>> DEBUG buscarInputFrescoSinFallar: no se pudo releer '" + ariaLabel
                + "' tras " + intentos + " intentos (probablemente el árbol de semántica se está "
                + "reconstruyendo justo en este instante); se continúa sin este chequeo diagnóstico.");
        return null;
    }

    // Tras tocar "Generar Notas", la app no genera el reporte directo con los
    // filtros: abre un panel adicional donde hay que (1) elegir un usuario en
    // un desplegable, (2) escribir el contenido del reporte en un campo de
    // texto, y (3) tocar el botón "Subir Reporte" para confirmar. Este método
    // automatiza esos tres pasos.
    //
    // OJO: no se conoce el aria-label exacto del campo de texto del reporte
    // (no estaba visible en el DOM que teníamos), así que se prueban varias
    // etiquetas candidatas. Si ninguna coincide, revisar el archivo
    // target/debug-SEL03-dialogoSubirReporte-*.html (guardado por este mismo
    // método) para ver el aria-label real y agregarlo al arreglo
    // POSIBLES_ETIQUETAS_TEXTO_REPORTE.
    private static final String[] POSIBLES_ETIQUETAS_TEXTO_REPORTE = {
            "Escribe tu reporte aquí...", "Reporte", "Escribe el reporte", "Mensaje", "Nota", "Descripción", "Descripcion"
    };

    private void manejarDialogoSubirReporte() {
        WebElement dialogoTexto = esperarElementoConTexto(10, "Selecciona usuario", "Subir Reporte");
        if (dialogoTexto == null) {
            System.out.println(">>> DEBUG manejarDialogoSubirReporte: no apareció el panel 'Selecciona usuario / "
                    + "Subir Reporte' tras el click en 'Generar Notas'; se asume que no es necesario en este flujo.");
            return;
        }

        System.out.println(">>> DEBUG manejarDialogoSubirReporte: apareció el panel para seleccionar usuario "
                + "y escribir el reporte.");
        capturarEvidencia("debug-SEL03-dialogoSubirReporte-" + System.currentTimeMillis());

        // 1) Abrir el desplegable "Selecciona usuario" y elegir la primera opción disponible
        WebElement dropdownUsuario = esperarBotonConTexto(10, "Selecciona usuario");
        if (dropdownUsuario == null) {
            System.out.println(">>> DEBUG manejarDialogoSubirReporte: no se encontró el control "
                    + "'Selecciona usuario' para abrirlo.");
        } else {
            clickForzado(dropdownUsuario);
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            // Flutter suele pintar las opciones de un dropdown abierto como
            // flt-semantics con role='menuitem' o role='option'
            List<WebElement> opciones = driver.findElements(By.xpath(
                    "//flt-semantics[@role='menuitem' or @role='option']"));
            System.out.println(">>> DEBUG manejarDialogoSubirReporte: opciones de usuario encontradas: "
                    + opciones.size());
            for (int i = 0; i < opciones.size(); i++) {
                try {
                    WebElement o = opciones.get(i);
                    System.out.println("    [" + i + "] displayed=" + o.isDisplayed()
                            + " aria-label=" + o.getAttribute("aria-label"));
                } catch (Exception ignorado) {
                }
            }

            WebElement primeraOpcionVisible = null;
            for (WebElement o : opciones) {
                try {
                    if (!o.isDisplayed()) {
                        continue;
                    }
                    org.openqa.selenium.Rectangle r = o.getRect();
                    if ((long) r.getWidth() * (long) r.getHeight() > 0) {
                        primeraOpcionVisible = o;
                        break;
                    }
                } catch (Exception ignorado) {
                }
            }

            if (primeraOpcionVisible != null) {
                clickForzado(primeraOpcionVisible);
                System.out.println(">>> DEBUG manejarDialogoSubirReporte: se seleccionó la primera opción "
                        + "de usuario disponible.");
            } else {
                System.out.println(">>> DEBUG manejarDialogoSubirReporte: ADVERTENCIA - no se encontró ninguna "
                        + "opción de usuario visible tras abrir el desplegable. Revisar la evidencia guardada "
                        + "(target/debug-SEL03-dialogoSubirReporte-*.html) para ajustar el selector de opciones.");
            }
        }

        try {
            Thread.sleep(300);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // 2) Escribir el contenido del reporte
        boolean escrito = false;
        for (String etiqueta : POSIBLES_ETIQUETAS_TEXTO_REPORTE) {
            List<WebElement> candidatos = driver.findElements(By.xpath(
                    "//input[@aria-label='" + etiqueta + "'] | //textarea[@aria-label='" + etiqueta + "']"));
            if (!candidatos.isEmpty()) {
                System.out.println(">>> DEBUG manejarDialogoSubirReporte: encontrado campo de texto con "
                        + "aria-label='" + etiqueta + "'");
                escribirEnCampo(etiqueta, "Reporte generado automáticamente por prueba Selenium "
                        + System.currentTimeMillis());
                escrito = true;
                break;
            }
        }

        if (!escrito) {
            System.out.println(">>> DEBUG manejarDialogoSubirReporte: ADVERTENCIA - no se encontró ningún campo "
                    + "de texto conocido para escribir el reporte. Revisar "
                    + "target/debug-SEL03-dialogoSubirReporte-*.html para obtener el aria-label real y "
                    + "agregarlo a POSIBLES_ETIQUETAS_TEXTO_REPORTE.");
        }

        // 3) Confirmar con el botón "Subir Reporte"
        WebElement botonSubir = esperarBotonConTexto(10, "Subir Reporte");
        if (botonSubir == null) {
            System.out.println(">>> DEBUG manejarDialogoSubirReporte: no se encontró el botón 'Subir Reporte'.");
        } else {
            clickForzado(botonSubir);
            System.out.println(">>> DEBUG manejarDialogoSubirReporte: click en 'Subir Reporte' realizado.");
        }

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // Diagnóstico: qué aviso (si alguno) aparece justo tras confirmar
        String avisoTrasSubir = detectarAvisoVisible();
        System.out.println(">>> DEBUG manejarDialogoSubirReporte: aviso visible justo tras 'Subir Reporte': ["
                + avisoTrasSubir + "]");
        capturarEvidencia("debug-SEL03-trasSubirReporte-" + System.currentTimeMillis());

        // Muchas apps Flutter muestran un diálogo de confirmación (p.ej.
        // "Reporte subido con éxito" + botón OK/Aceptar) tras el submit.
        // Mientras ese diálogo siga abierto, Flutter no reconstruye el
        // árbol de semántica de la lista de fondo, así que el test nunca
        // "ve" el ítem nuevo aunque ya se haya guardado. Se intenta cerrar
        // ese aviso automáticamente.
        cerrarAvisoSiExiste();

        boolean panelSigueAbierto = esperarElementoConTexto(3, "Selecciona usuario", "Subir Reporte") != null;
        if (panelSigueAbierto) {
            System.out.println(">>> DEBUG manejarDialogoSubirReporte: el panel/diálogo sigue abierto tras el "
                    + "submit; se intentará cerrar con ESC como último recurso.");
            try {
                new Actions(driver).sendKeys(Keys.ESCAPE).perform();
                Thread.sleep(500);
            } catch (Exception eEsc) {
                System.out.println(">>> DEBUG manejarDialogoSubirReporte: no se pudo enviar ESC ("
                        + eEsc.getMessage() + ")");
            }
        }
    }

    // Busca un botón visible con alguno de los textos de cierre típicos
    // ("OK", "Aceptar", "Cerrar", "Entendido") dentro de un aviso/diálogo
    // de confirmación y lo clickea. No hace nada si no encuentra ninguno.
    private void cerrarAvisoSiExiste() {
        String[] textosCierre = {"OK", "Aceptar", "Cerrar", "Entendido"};
        for (String texto : textosCierre) {
            List<WebElement> candidatos = driver.findElements(
                    By.xpath("//flt-semantics[contains(., '" + texto + "')]"));
            for (WebElement c : candidatos) {
                try {
                    if (!c.isDisplayed()) {
                        continue;
                    }
                    org.openqa.selenium.Rectangle r = c.getRect();
                    if ((long) r.getWidth() * (long) r.getHeight() <= 0) {
                        continue;
                    }
                    System.out.println(">>> DEBUG cerrarAvisoSiExiste: se encontró y clickeó un botón de cierre "
                            + "con texto '" + texto + "'");
                    clickForzado(c);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                } catch (Exception ignorado) {
                }
            }
        }
        System.out.println(">>> DEBUG cerrarAvisoSiExiste: no se encontró ningún botón de cierre conocido "
                + "(OK/Aceptar/Cerrar/Entendido); puede que no haya aparecido ningún aviso de confirmación.");
    }

    private void navegarAReportes() {
        String urlAntes = driver.getCurrentUrl();

        WebElement linkReportes = esperarBotonConTexto(15, "Notas");

        if (linkReportes != null) {
            try {
                clickForzado(linkReportes);

                new WebDriverWait(driver, Duration.ofSeconds(20))
                        .until(d -> !d.getCurrentUrl().equals(urlAntes) || d.getCurrentUrl().toLowerCase().contains("reportes"));

                System.out.println(">>> DEBUG navegarAReportes: navegación in-app OK, URL actual: " + driver.getCurrentUrl());
                return;
            } catch (Exception e) {
                System.out.println(">>> DEBUG navegarAReportes: se encontró el botón 'Notas' pero el click no navegó "
                        + "a tiempo (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "), se usará driver.get() como fallback. OJO: esto hace una recarga completa del navegador "
                        + "y puede perder la sesión de Firebase Auth si su persistencia no es LOCAL.");
            }
        } else {
            System.out.println(">>> DEBUG navegarAReportes: no apareció el botón 'Notas' en 15s "
                    + "(la barra de navegación puede no haber terminado de montar todavía), "
                    + "se usará driver.get() como fallback. OJO: esto hace una recarga completa del navegador "
                    + "y puede perder la sesión de Firebase Auth si su persistencia no es LOCAL.");
        }

        driver.get(BASE_URL + "/#/reportes");
        enableFlutterAccessibility();

        if (driver.getCurrentUrl().toLowerCase().contains("login")) {
            System.out.println(">>> DEBUG navegarAReportes: tras driver.get('/#/reportes') la app te devolvió "
                    + "a /login. Esto confirma que la recarga completa del navegador perdió la sesión de "
                    + "Firebase Auth (bug de la app / configuración de persistencia), no un problema de Selenium.");
        }
    }

    private void login(String email, String password) {
        driver.get(BASE_URL + "/#/login");
        enableFlutterAccessibility();

        try {
            wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            By.xpath("//input[@aria-label='Ingresa tu correo']")));
        } catch (TimeoutException e) {
            capturarEvidencia("fallo-login-" + System.currentTimeMillis());
            throw e;
        }

        esperarCampoHabilitado("Ingresa tu correo", 20);

        escribirEnCampo("Ingresa tu correo", email);

        esperarCampoHabilitado("Ingresa tu contraseña", 5);

        escribirEnCampo("Ingresa tu contraseña", password);

        WebElement correoFresco = buscarInputFrescoSinFallar("Ingresa tu correo", 8);
        WebElement passwordFresco = buscarInputFrescoSinFallar("Ingresa tu contraseña", 8);

        if (correoFresco == null || passwordFresco == null) {
            System.out.println(">>> DEBUG login: no se pudo hacer el chequeo diagnóstico previo al click "
                    + "(esto es solo informativo, se continúa igual con el click en el botón)");
        } else {
            System.out.println(">>> DEBUG valor correo antes de click: ["
                    + correoFresco.getAttribute("value") + "]");
            System.out.println(">>> DEBUG valor password antes de click: ["
                    + passwordFresco.getAttribute("value") + "]");

            if (!correoFresco.isEnabled() || !passwordFresco.isEnabled()) {
                String nombreEvidencia = "fallo-loginCamposDeshabilitados-" + System.currentTimeMillis();
                capturarEvidencia(nombreEvidencia);
                fail("Los campos de login siguen deshabilitados (enabled=false) después de escribir. "
                        + "correo.enabled=" + correoFresco.isEnabled()
                        + " password.enabled=" + passwordFresco.isEnabled()
                        + ". Esto indica un problema en la app (el TextField de Flutter en login.dart "
                        + "está deshabilitado por alguna condición que nunca se cumple), no un problema "
                        + "de este test -- verificar manualmente si se puede escribir en el formulario "
                        + "de login con un navegador normal. Evidencia en target/" + nombreEvidencia + ".html");
            }
        }

        WebElement botonLogin = encontrarBotonPorTexto("Iniciar Sesión");
        clickForzado(botonLogin);

        boolean navego = false;
        try {
            new WebDriverWait(driver, Duration.ofSeconds(25))
                    .until(d -> !d.getCurrentUrl().toLowerCase().contains("login"));
            navego = true;
        } catch (TimeoutException ignored) {
        }

        if (!navego) {
            String textoAviso = detectarAvisoVisible();

            boolean avisoEnHtml = driver.getPageSource().contains("Aviso");

            System.out.println(">>> DEBUG login no navegó. Aviso visible (detectado): [" + textoAviso + "]");
            System.out.println(">>> DEBUG ¿la palabra 'Aviso' aparece en el HTML crudo?: " + avisoEnHtml);
            System.out.println(">>> DEBUG URL actual: " + driver.getCurrentUrl());

            String nombreEvidencia = "fallo-loginPostClick-" + System.currentTimeMillis();
            capturarEvidencia(nombreEvidencia);
            fail("Login no navegó fuera de /login tras el click. Aviso detectado: " + textoAviso
                    + " | ¿'Aviso' en HTML crudo?: " + avisoEnHtml
                    + " -- revisar target/" + nombreEvidencia + ".html para ver el DOM completo en ese instante.");
        }
    }

    @Test
    @DisplayName("SEL-01: Login con credenciales válidas")
    void testLoginExitoso() {
        login(USER_EMAIL, USER_PASSWORD);
        assertFalse(driver.getCurrentUrl().toLowerCase().contains("login"),
                "Después de un login válido no debería seguir en /login");
    }

    @Test
    @DisplayName("SEL-01b: Login con contraseña incorrecta muestra error")
    void testLoginFallido() {
        driver.get(BASE_URL + "/#/login");
        enableFlutterAccessibility();

        try {
            wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            By.xpath("//input[@aria-label='Ingresa tu correo']")));
        } catch (TimeoutException e) {
            capturarEvidencia("fallo-loginFallido-" + System.currentTimeMillis());
            throw e;
        }

        esperarCampoHabilitado("Ingresa tu correo", 20);
        escribirEnCampo("Ingresa tu correo", USER_EMAIL);
        esperarCampoHabilitado("Ingresa tu contraseña", 5);
        escribirEnCampo("Ingresa tu contraseña", "ClaveIncorrecta123");

        WebElement botonLogin = encontrarBotonPorTexto("Iniciar Sesión");
        clickForzado(botonLogin);

        String textoAviso = "(ninguno)";
        long limite = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < limite) {
            textoAviso = detectarAvisoVisible();
            if (!"(ninguno)".equals(textoAviso)) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println(">>> DEBUG aviso visible tras login fallido: [" + textoAviso + "]");

        if ("(ninguno)".equals(textoAviso)) {
            capturarEvidencia("fallo-loginFallidoSinAviso-" + System.currentTimeMillis());
        }

        assertNotEquals("(ninguno)", textoAviso,
                "Se esperaba algún mensaje de error visible tras un login con contraseña incorrecta");
        assertTrue(driver.getCurrentUrl().toLowerCase().contains("login") || driver.getCurrentUrl().equals(BASE_URL + "/"));
    }

    @Test
    @DisplayName("SEL-02: Completa el formulario de registro (texto)")
    void testCompletarFormularioRegistro() {
        driver.get(BASE_URL + "/#/register");
        enableFlutterAccessibility();

        wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//input[@aria-label='Nombre']")));
        escribirEnCampo("Nombre", "Usuario Selenium");

        String correoPrueba = "selenium_" + System.currentTimeMillis() + "@test.com";
        escribirEnCampo("Correo", correoPrueba);
        escribirEnCampo("Contraseña", "Clave123!");
        escribirEnCampo("Teléfono", "3001234567");

        WebElement campoNombreFresco = buscarInputFresco("Nombre");
        WebElement campoCorreoFresco = buscarInputFresco("Correo");

        assertEquals("Usuario Selenium", campoNombreFresco.getAttribute("value"));
        assertEquals(correoPrueba, campoCorreoFresco.getAttribute("value"));
    }

    @Test
    @DisplayName("SEL-03: Jefe sube un reporte y aparece en 'Reportes recientes'")
    void testJefeSubeReporte() {
        login(JEFE_EMAIL, JEFE_PASSWORD);

        navegarAReportes();

        if (driver.getCurrentUrl().toLowerCase().contains("login")) {
            String nombreEvidencia = "fallo-SEL03-sesionPerdida-" + System.currentTimeMillis();
            capturarEvidencia(nombreEvidencia);
            fail("Tras navegar a Reportes, la app volvió a /login (sesión perdida). "
                    + "Revisar target/" + nombreEvidencia + ".html.");
        }

        String fechaHoy = java.time.LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        int cantidadAntes = contarElementosConTexto(fechaHoy);

        WebElement botonGenerarNotas = esperarBotonConTexto(20, "Generar Notas");
        if (botonGenerarNotas == null) {
            String nombreEvidencia = "fallo-SEL03-botonGenerarNotas-" + System.currentTimeMillis();
            capturarEvidencia(nombreEvidencia);
            fail("No apareció el botón 'Generar Notas' en la pantalla de Notas tras 20s. "
                    + "Revisar target/" + nombreEvidencia + ".html.");
        }

        // El árbol de semántica de Flutter no siempre ordena los elementos
        // igual entre corridas (depende de si hubo o no recarga completa de
        // página), así que a veces esperarBotonConTexto puede clickear un
        // elemento equivocado con el mismo texto (p.ej. una etiqueta en vez
        // del botón real). Para blindar esto, se verifica que el panel
        // "Selecciona usuario / Subir Reporte" realmente aparezca tras el
        // click; si no aparece, se re-busca el botón desde cero y se
        // reintenta, hasta 3 veces.
        boolean panelAparecio = false;
        String avisoTrasClick = "(ninguno)";
        for (int intento = 1; intento <= 3 && botonGenerarNotas != null; intento++) {
            clickForzado(botonGenerarNotas);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            avisoTrasClick = detectarAvisoVisible();
            System.out.println(">>> DEBUG SEL03: intento " + intento + "/3 - aviso visible tras click en "
                    + "'Generar Notas': [" + avisoTrasClick + "]");

            panelAparecio = esperarElementoConTexto(5, "Selecciona usuario", "Subir Reporte") != null;
            if (panelAparecio) {
                break;
            }

            System.out.println(">>> DEBUG SEL03: el panel no apareció en el intento " + intento
                    + "/3; se re-buscará el botón 'Generar Notas' y se reintentará.");
            botonGenerarNotas = esperarBotonConTexto(10, "Generar Notas");
        }

        if (!panelAparecio) {
            String nombreEvidencia = "fallo-SEL03-panelSubirReporteNoAparecio-" + System.currentTimeMillis();
            capturarEvidencia(nombreEvidencia);
            fail("Tras 3 intentos, no apareció el panel 'Selecciona usuario / Subir Reporte' al tocar "
                    + "'Generar Notas'. Último aviso detectado: [" + avisoTrasClick + "]. "
                    + "Revisar target/" + nombreEvidencia + ".html.");
        }

        manejarDialogoSubirReporte();

        boolean aparecioNuevoReporte = false;
        long limite = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < limite) {
            if (contarElementosConTexto(fechaHoy) > cantidadAntes) {
                aparecioNuevoReporte = true;
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!aparecioNuevoReporte) {
            String nombreEvidencia = "fallo-SEL03-reporteEnLista-" + System.currentTimeMillis();
            capturarEvidencia(nombreEvidencia);
            fail("Tras tocar 'Generar Notas', seleccionar usuario, escribir el reporte y tocar 'Subir Reporte', "
                    + "no apareció ningún ítem nuevo con la fecha de hoy (" + fechaHoy + ") en 'Reportes recientes' "
                    + "tras 30s. Aviso visible justo tras el primer click: [" + avisoTrasClick + "]. "
                    + "Revisar target/" + nombreEvidencia + ".html, y también los archivos "
                    + "target/debug-SEL03-dialogoSubirReporte-*.html generados durante el flujo, para confirmar "
                    + "si el usuario/campo de texto se seleccionaron correctamente (ver logs de consola "
                    + "'manejarDialogoSubirReporte').");
        }

        assertTrue(aparecioNuevoReporte, "Se esperaba un nuevo reporte generado con la fecha de hoy");
    }

    @Test
    @DisplayName("SEL-04: Usuario no-jefe ve sus reportes sin loader infinito")
    void testUsuarioNoJefeVeReportes() {
        login(USER_EMAIL, USER_PASSWORD);

        navegarAReportes();

        if (driver.getCurrentUrl().toLowerCase().contains("login")) {
            String nombreEvidencia = "fallo-SEL04-sesionPerdida-" + System.currentTimeMillis();
            capturarEvidencia(nombreEvidencia);
            fail("Tras navegar a Reportes, la app volvió a /login (sesión perdida). "
                    + "Revisar target/" + nombreEvidencia + ".html.");
        }

        WebElement resultado = esperarElementoConTexto(30, "Todavía no hay reportes", "reporte");
        if (resultado == null) {
            String nombreEvidencia = "fallo-SEL04-listaReportes-" + System.currentTimeMillis();
            capturarEvidencia(nombreEvidencia);
            fail("No apareció ni la lista de reportes ni el mensaje 'Todavía no hay reportes.' en 30s. "
                    + "Revisar target/" + nombreEvidencia + ".html para ver el DOM completo en ese instante.");
        }

        assertTrue(resultado.isDisplayed());
    }
}