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

/**
 * Pruebas Selenium (Sprint 5) - Eco-Refill-NBFV
 * ---------------------------------------------
 * Los localizadores (aria-label) de este archivo se tomaron directamente
 * del código fuente real de la app (hintText / labelText de cada campo en
 * frontend/lib/screens/login.dart, register.dart y reportes.dart), no son
 * un punto de partida a ajustar: ya corresponden a los valores reales.
 *
 * IMPORTANTE sobre Flutter Web + Selenium:
 * Flutter Web dibuja la interfaz sobre un <canvas> (renderer CanvasKit),
 * pero SIEMPRE crea elementos <input>/<textarea> reales y ocultos para
 * capturar el texto que escribís (así funciona el teclado). Esos <input>
 * exponen su hint/label como atributo aria-label cuando el árbol de
 * semántica está activo. Flutter Web activa ese árbol automáticamente en
 * cuanto detecta interacción de accesibilidad (o el primer foco/tab), por
 * eso a veces el primer "click" tarda un poco más: dale tiempo con
 * WebDriverWait, no uses Thread.sleep fijo salvo como margen extra.
 *
 * FIX (Sprint 5 - bug login): Flutter Web puede regenerar el nodo DOM de
 * un <input> cuando el foco pasa a otro campo (reconstruye su árbol de
 * semántica). Cuando eso pasa, la referencia WebElement que ya tenías en
 * Java sigue "viva" en el navegador y getAttribute("value") te sigue
 * devolviendo el texto -- pero ese nodo quedó desconectado del
 * TextEditingController real de Flutter, que nunca se enteró del texto.
 * Resultado: el formulario ve los campos vacíos y muestra "Por favor
 * ingresa correo y contraseña" aunque el DOM muestre el valor correcto.
 * Para evitarlo:
 *   1) Nunca reutilizar una referencia WebElement de un campo después de
 *      haber cambiado el foco a otro campo; volver a buscarlo (fresco).
 *   2) Dar un pequeño respiro (pausa corta) después de escribir en cada
 *      campo para que Flutter procese el evento 'input' antes de mover
 *      el foco.
 *   3) Verificar (log de DEBUG) el valor del campo ya "fresco" justo
 *      antes del click, no el de la referencia original.
 *
 * FIX (2da vuelta - el click en "Iniciar Sesión" no hacía nada):
 * con los campos ya sincronizados correctamente, el login seguía sin
 * navegar y sin mostrar ningún aviso de error. La causa más probable es
 * que el XPath del botón matcheaba MÁS DE UN <flt-semantics> (uno
 * clickeable de verdad y otro invisible/superpuesto de otra capa de
 * semántica), y Selenium hacía click en el que no correspondía. Se
 * agregó:
 *   1) encontrarBotonPorTexto(): loguea TODOS los candidatos que matchean
 *      el texto del botón (cuántos son, si están visibles, su tamaño) y
 *      elige el primero realmente visible y con dimensiones > 0.
 *   2) clickForzado(): si el click nativo de Selenium no tiene efecto
 *      (por ejemplo por overlays), cae a un click disparado por
 *      JavaScript.
 *   3) detectarAvisoVisible(): en vez de buscar solo palabras sueltas
 *      específicas, primero busca cualquier elemento con
 *      role='dialog'/'alertdialog'/'alert', que es más robusto a
 *      cambios de texto exacto.
 *   4) El wait posterior al click de login() se subió a 25s (por si el
 *      primer login contra Firebase Auth tarda más que el resto de las
 *      esperas).
 *
 * FIX (9na vuelta - SEL-03 / SEL-04 con timeout en /reportes):
 * después de un login exitoso, ambos tests navegan con
 * driver.get(BASE_URL + "/#/reportes"). Eso es una recarga COMPLETA de
 * la página -- un Flutter nuevo desde cero, con su propio
 * flt-semantics-placeholder sin clickear. Nunca se volvía a llamar
 * enableFlutterAccessibility() después de esa recarga, así que el árbol
 * de semántica no estaba activo en la nueva instancia y ningún
 * aria-label llegaba a aparecer (por eso ExpectedConditions.visibilityOf
 * ...  nunca encontraba el dropdown ni el texto de "reportes", y el test
 * terminaba en TimeoutException). Se agregó una llamada a
 * enableFlutterAccessibility() inmediatamente después de cada
 * driver.get(".../#/reportes").
 *
 * FIX (10ma vuelta - SEL-02 flaky, "Nombre" a veces queda vacío):
 * escribirEnCampo() ya verificaba el valor final y lo logueaba como
 * ADVERTENCIA si no coincidía, pero no hacía nada más -- si esa única
 * pasada coincidía con una reconstrucción del árbol de semántica de
 * Flutter, el campo quedaba vacío y el test fallaba sin haber
 * reintentado. Se separó la lógica en intentarEscribirEnCampo() (mismo
 * comportamiento de antes, pero devuelve boolean según si el valor final
 * coincidió) y escribirEnCampo() ahora es un wrapper que reintenta hasta
 * 3 veces antes de rendirse.
 *
 * FIX (11va vuelta - falso negativo de escribirEnCampo en LOGIN): el log
 * mostró que fallback 1 escribía bien el texto ("OK") pero la
 * verificación final SIEMPRE reportaba 0 candidatos al re-buscar por
 * aria-label, dando "ADVERTENCIA FINAL - no se pudo escribir" aunque el
 * login funcionara de verdad (se confirmó porque el "Aviso" mostrado era
 * el de credenciales inválidas, no el de campos vacíos). Causa: en
 * login.dart el aria-label está atado al HINT del campo, que Flutter dej
 * de exponer en el árbol de semántica en cuanto el campo tiene contenido
 * -- researcher por ese aria-label después de escribir da 0 SIEMPRE, sin
 * importar si el texto se escribió bien o no. Fix: ahora se guarda la
 * referencia real al WebElement usado para escribir (campoUsado) y se
 * verifica con esa misma referencia en vez de re-buscar por aria-label;
 * solo si esa referencia queda "stale" se cae al researcher viejo.
 *
 * FIX (12va vuelta - SEL-03/SEL-04 seguían con timeout): con
 * enableFlutterAccessibility() ya reactivado tras el driver.get(...), el
 * dropdown "Selecciona usuario" (SEL-03) y la lista/mensaje de reportes
 * (SEL-04) todavía no aparecían en 15s. Como /reportes probablemente
 * dependa de una consulta a Firestore que en un hosting frío puede
 * tardar más que eso (mismo motivo por el que login() ya usa 25s), se
 * subió el timeout de esas dos esperas a 30s y se agregó
 * capturarEvidencia() en el catch -- antes no se guardaba nada ahí, así
 * que si el problema persiste, target/fallo-SEL03-...html y
 * target/fallo-SEL04-...html van a mostrar la causa real.
 *
 * FIX (13va vuelta - causa raíz REAL de SEL-03/SEL-04, no era timing):
 * el HTML de evidencia mostró que el texto que buscábamos ("Todavía no
 * hay reportes.", el texto del reporte recién subido, los nombres de los
 * ítems del menú como "Inicio") NUNCA estuvo en un atributo aria-label:
 * está como CONTENIDO de texto dentro de un <span> hijo de
 * <flt-semantics>, ej.:
 *   <flt-semantics role="button">Inicio</flt-semantics>
 *   <flt-semantics>...<span>Todavía no hay reportes.</span>...</flt-semantics>
 * Por eso contains(@aria-label, '...') daba 0 candidatos por más que se
 * esperara 30s, 5 minutos o una hora: no era un problema de timing sino
 * de que el XPath apuntaba al atributo equivocado. Esto nunca afectó a
 * encontrarBotonPorTexto() ni a detectarAvisoVisible() porque esos ya
 * usan contains(., 'texto') (contenido de texto, no atributo) -- el
 * patrón correcto para este tipo de nodos.
 * Fix: se agregó esperarElementoConTexto(), que generaliza ese mismo
 * patrón (contains(., 'texto'), con selección del candidato visible de
 * menor área para no quedarse con un contenedor gigante que también
 * "contiene" el texto) en un loop con timeout configurable. Se usa ahora
 * en SEL-04 (mensaje de "no hay reportes" / lista) y en SEL-03
 * (verificación de que el reporte recién subido aparece en la lista),
 * que tenía exactamente el mismo bug.
 *
 * Configurar antes de correr:
 *   - BASE_URL apunta a tu app en Firebase Hosting.
 *   - USER_EMAIL / USER_PASSWORD son credenciales de un usuario NO-jefe
 *     ya registrado en Firebase Auth (para el login normal y SEL-04).
 *   - JEFE_EMAIL / JEFE_PASSWORD son las de un usuario "jefe" (es el único
 *     rol que puede escribir/subir un reporte, usado en SEL-03).
 */
public class EcoRefillSeleniumTest {

    private static final String BASE_URL = "https://eco-refill-31771.web.app";

    // Usuario normal (no-jefe)
    private static final String USER_EMAIL = "cristian3@gmail.com";
    private static final String USER_PASSWORD = "123456";

    // Usuario jefe (único rol habilitado para subir reportes)
    private static final String JEFE_EMAIL = "villalba@gmail.com";
    private static final String JEFE_PASSWORD = "1234567";

    // Pausa corta para darle tiempo a Flutter de procesar el evento
    // 'input' antes de mover el foco a otro campo o hacer click.
    private static final Duration PAUSA_SYNC_FLUTTER = Duration.ofMillis(350);

    // Cantidad máxima de reintentos completos de escribirEnCampo() antes
    // de rendirse (ver FIX 10ma vuelta).
    private static final int MAX_INTENTOS_ESCRIBIR = 3;

    private WebDriver driver;
    private WebDriverWait wait;

    @BeforeAll
    static void setupClass() {
        // Descarga automáticamente la versión de chromedriver que coincide
        // con el Chrome instalado en esta máquina.
        WebDriverManager.chromedriver().setup();
    }

    @BeforeEach
    void setup() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--window-size=1280,900");
        // Comentar la siguiente línea si querés VER el navegador durante
        // la grabación del video de evidencia.
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

    // -----------------------------------------------------------------
    // Utilidad de diagnóstico: cuando algo no aparece a tiempo, en vez de
    // seguir adivinando, guardamos una captura de pantalla y el HTML
    // completo de la página en ese instante. Así se puede ver exactamente
    // qué había cargado (o no) en el navegador cuando falló el test.
    // Los archivos quedan en la carpeta target/ del proyecto, con nombres
    // como "target/fallo-login-<timestamp>.png" y ".html".
    // -----------------------------------------------------------------
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

    // -----------------------------------------------------------------
    // FIX (4ta vuelta - causa raíz encontrada): el diálogo real "Por
    // favor ingresa correo y contraseña" seguía apareciendo con datos
    // correctos en el atributo value del <input>. Eso significa que
    // escribirEnCampo() estaba cayendo SIEMPRE al fallback por
    // JavaScript (setter de value + evento input/change sintético), no
    // al camino principal (click + sendKeys reales) -- y el fallback por
    // JS deja el atributo value con el texto correcto, pero JAMÁS
    // actualiza el TextEditingController real de Flutter (por eso el
    // "Aviso" seguía diciendo que los campos estaban vacíos). El catch
    // original no logueaba nada, así que este bug quedaba invisible.
    //
    // Ahora:
    //   1) El catch imprime SIEMPRE qué pasó y por qué se cayó el
    //      método principal.
    //   2) Se agregó un método INTERMEDIO más confiable que el fallback
    //      por JS: enfocar el campo por JavaScript (que no tiene
    //      restricciones de "interactability" del navegador) y después
    //      usar Actions().sendKeys() SIN click previo -- esto manda
    //      eventos de teclado reales (keydown/keypress/input/keyup) al
    //      elemento que ya tiene foco, que es justo lo que el
    //      TextEditingController de Flutter necesita para enterarse.
    //   3) El setter de value por JS puro queda como ÚLTIMO recurso,
    //      documentado como no confiable para Flutter Web.
    // -----------------------------------------------------------------
    // -----------------------------------------------------------------
    // FIX (5ta vuelta - causa raíz real): el <input> oculto detrás del
    // canvas de Flutter Web (CanvasKit) NO es el elemento que hay que
    // clickear para que Flutter conecte el foco a su TextInputConnection
    // real. El elemento que Flutter efectivamente escucha para eso es el
    // nodo de accesibilidad <flt-semantics role="textbox"> que se dibuja
    // ENCIMA del input (mismo aria-label). Clickear el <input> directamente
    // puede:
    //   a) fallar con InvalidElementStateException (el navegador considera
    //      que el input está "tapado" por el canvas y no es interactuable), o
    //   b) "funcionar" sin excepción pero sin que Flutter se entere, porque
    //      el evento de click nunca pasó por su GestureDetector semántico.
    //
    // Ahora el método principal es: buscar el <flt-semantics
    // aria-label='...' role='textbox'>, clickearlo con Actions (que sí
    // dispara los eventos de puntero que Flutter escucha), y recién ahí
    // mandar las teclas reales con Actions().sendKeys() (sin click extra,
    // porque el foco ya quedó puesto).
    //
    // El viejo camino (click+sendKeys directo sobre el <input>, y el
    // fallback de foco-por-JS) se conservan como redes de seguridad por si
    // en algún estado del árbol de semántica el flt-semantics no aparece,
    // pero ya no son el método esperado en el camino feliz.
    //
    // Además: al final SIEMPRE se relee el <input> fresco y se compara su
    // value contra el texto esperado. Si no coincide, se loguea como
    // ADVERTENCIA explícita -- así un futuro fallo "silencioso" (como el de
    // este log, donde el fallback decía "OK" pero el valor real quedaba
    // vacío) no pasa desapercibido.
    // -----------------------------------------------------------------
    // FIX (10ma vuelta): este método antes se llamaba escribirEnCampo() y
    // era void. Ahora devuelve boolean (true si el valor final coincidió
    // con lo esperado) y el wrapper público escribirEnCampo() de más
    // abajo lo reintenta hasta MAX_INTENTOS_ESCRIBIR veces.
    // FIX (11va vuelta - falso negativo en LOGIN): el log mostró que en
    // login.dart el aria-label del <input> está atado al hint text
    // ("Ingresa tu correo"). En cuanto el campo tiene contenido, Flutter
    // deja de exponer ese hint en el árbol de semántica -- por eso
    // volver a buscar el input POR ESE MISMO aria-label después de
    // escribir siempre devolvía 0 candidatos, aunque fallback 1 hubiera
    // escrito el texto perfectamente (se confirmó: el login igual
    // funcionaba, mostrando el "Aviso" real de credenciales inválidas
    // en vez del de "campos vacíos"). En register.dart el aria-label es
    // una ETIQUETA persistente ("Nombre", "Correo"...), no un hint, así
    // que ahí la researcher post-escritura sí funciona.
    //
    // Fix: en vez de siempre re-buscar por aria-label para verificar,
    // guardamos la referencia AL MISMO WebElement que se usó para
    // escribir (campoUsado) y verificamos su value directamente con esa
    // referencia -- sin volver a buscarlo por aria-label. Solo si esa
    // referencia quedó "stale" (Flutter reconstruyó el nodo) caemos al
    // método viejo (researcher por aria-label) como red de seguridad.
    private boolean intentarEscribirEnCampo(String ariaLabel, String texto) {
        boolean exitoso = false;
        WebElement campoUsado = null;

        // MÉTODO PRINCIPAL: clickear el nodo de semántica (flt-semantics),
        // no el <input> oculto.
        try {
            List<WebElement> nodosSemanticos = driver.findElements(
                    By.xpath("//flt-semantics[@aria-label='" + ariaLabel + "' and @role='textbox']"));

            if (nodosSemanticos.isEmpty()) {
                System.out.println(">>> DEBUG escribirEnCampo: no se encontró flt-semantics[role='textbox'] "
                        + "para aria-label='" + ariaLabel + "', se intentará método alternativo");
            } else {
                WebElement nodoSemantico = nodosSemanticos.get(0);
                new Actions(driver).moveToElement(nodoSemantico).click().perform();

                // Limpiamos con teclado real por si el campo ya tenía texto
                // de un intento previo, y escribimos el texto nuevo.
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

        // FALLBACK 1 (viejo método principal): click+sendKeys directo sobre
        // el <input> oculto, buscado fresco.
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

        // FALLBACK 2: foco por JS + sendKeys real (sin click previo).
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

        // ÚLTIMO RECURSO: set value por JS puro (documentado como NO
        // confiable para Flutter Web, se deja solo para no dejar el campo
        // completamente vacío).
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

        // Pequeño respiro para que Flutter procese el evento 'input' antes
        // de mover el foco a otro campo o hacer click.
        try {
            Thread.sleep(PAUSA_SYNC_FLUTTER.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // VERIFICACIÓN FINAL REAL: preferimos verificar con la MISMA
        // referencia que usamos para escribir (campoUsado), en vez de
        // re-buscar por aria-label -- en campos cuyo aria-label está atado
        // al hint (como en login.dart) esa researcher post-escritura
        // siempre da 0 candidatos aunque el texto se haya escrito bien
        // (ver FIX 11va vuelta). Solo si esa referencia quedó "stale"
        // caemos a la researcher por aria-label como red de seguridad.
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

    // -----------------------------------------------------------------
    // FIX (10ma vuelta): wrapper con reintentos. Los tests siguen
    // llamando a escribirEnCampo() exactamente igual que antes; puertas
    // adentro ahora reintenta hasta MAX_INTENTOS_ESCRIBIR veces el ciclo
    // completo (click/sendKeys + verificación final) si el valor no quedó
    // sincronizado, en vez de conformarse con una sola pasada.
    // -----------------------------------------------------------------
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


    // -----------------------------------------------------------------
    // FIX (13va vuelta - causa raíz real de SEL-03/SEL-04): las capturas
    // de evidencia mostraron que la app carga PERFECTO -- el texto
    // "Todavía no hay reportes." está literalmente en pantalla. El
    // problema nunca fue de red/Firestore: es que enableFlutterAccessibility()
    // asumía "si no encuentro flt-semantics-placeholder, la accesibilidad
    // ya está activa". Pero hay otra razón por la que esa lista puede
    // salir vacía: que Flutter TODAVÍA NO MONTÓ NADA en el DOM (la página
    // recién está cargando CanvasKit). Si el primer chequeo corre en ese
    // instante cero, la función se da por satisfecha por la razón
    // equivocada y nunca clickea el placeholder real que aparece unos
    // milisegundos después -- dejando la accesibilidad apagada para el
    // resto del test. TakesScreenshot() captura lo que se ve en el
    // <canvas> sin importar si la accesibilidad está activa, por eso la
    // imagen se veía perfecta mientras el DOM semántico estaba vacío por
    // debajo. En login/register la ventana de esta carrera es chica; en
    // /reportes (dashboard más pesado) se agranda y el bug se dispara.
    //
    // Fix: esperamos a que exista <flt-glass-pane> (el elemento raíz que
    // Flutter Web crea en cuanto monta algo) ANTES de mirar si el
    // placeholder está o no.
    // -----------------------------------------------------------------
    private void esperarFlutterMontado() {
        long limite = System.currentTimeMillis() + 15_000;

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

    // -----------------------------------------------------------------
    // Utilidad: activa el árbol de semántica de Flutter Web.
    // Flutter Web (CanvasKit) no genera los aria-label reales de los
    // inputs hasta que algo hace la PRIMERA interacción de accesibilidad.
    // Esa primera interacción la captura un elemento invisible que
    // Flutter agrega automáticamente: <flt-semantics-placeholder
    // aria-label="Enable accessibility">, que cubre toda la pantalla.
    // Un solo click no siempre alcanza a tiempo (a veces el navegador no
    // llega a "confirmarlo" antes de que Flutter reconstruya el DOM), así
    // que reintentamos en un bucle corto, buscando el elemento de nuevo en
    // cada vuelta para nunca usar una referencia vieja ("stale"). En
    // cuanto el placeholder desaparece del DOM, es señal de que la
    // accesibilidad ya quedó activa y salimos.
    //
    // IMPORTANTE: hay que volver a llamar a este método después de
    // CUALQUIER driver.get(...) -- cada navegación completa recrea la
    // app de Flutter desde cero, con su propio placeholder sin clickear
    // (ver FIX 9na vuelta, bug de SEL-03/SEL-04).
    // -----------------------------------------------------------------
    private void enableFlutterAccessibility() {
        // FIX (13va vuelta): primero confirmamos que Flutter ya montó algo
        // en el DOM, para no confundir "todavía no cargó nada" con "la
        // accesibilidad ya está activa".
        esperarFlutterMontado();

        long limite = System.currentTimeMillis() + 10_000; // 10s de margen

        while (System.currentTimeMillis() < limite) {
            List<WebElement> placeholders = driver.findElements(
                    By.cssSelector("flt-semantics-placeholder"));

            if (placeholders.isEmpty()) {
                // Ya no está: el árbol de semántica está activo (o nunca
                // hizo falta activarlo en esta sesión de navegador).
                return;
            }

            try {
                new Actions(driver).moveToElement(placeholders.get(0)).click().perform();
            } catch (Exception ignorado) {
                // El elemento pudo desaparecer justo durante el click
                // (porque sí funcionó); lo verificamos en la próxima vuelta.
            }

            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }

        }
    }

    // -----------------------------------------------------------------
    // Utilidad: busca por aria-label un <input> fresco (no reutiliza
    // referencias viejas). Ver nota de FIX en el header de la clase:
    // reutilizar un WebElement después de mover el foco a otro campo
    // puede quedar apuntando a un nodo que Flutter ya desconectó de su
    // TextEditingController real.
    // -----------------------------------------------------------------
    // -----------------------------------------------------------------
    // FIX (6ta vuelta - causa raíz real): el log mostró una asimetría
    // reveladora: en la pantalla de REGISTRO, el fallback 1 (click +
    // sendKeys directo sobre el <input>) funciona perfecto. En la
    // pantalla de LOGIN, el mismo fallback 1 explota con
    // InvalidElementStateException en clear(), y el fallback 2 (foco por
    // JS + sendKeys real) "reporta OK" pero el valor final queda vacío.
    // Esto es la MISMA firma que tuvimos con el botón "Iniciar Sesión"
    // en la 2da/3ra vuelta: driver.findElement() se queda con el PRIMER
    // elemento del documento que matchea el XPath, pero si hay más de un
    // <input> con el mismo aria-label (por ejemplo uno stale/oculto que
    // quedó de un render anterior del árbol de semántica, superpuesto al
    // real), buscarInputFresco() puede estar devolviendo el nodo
    // equivocado -- uno que técnicamente existe en el DOM pero no es el
    // que Flutter conectó a su TextEditingController real.
    //
    // Nunca habíamos aplicado a la búsqueda de inputs la misma corrección
    // que ya hace encontrarBotonPorTexto() para el botón: enumerar TODOS
    // los candidatos, loguear su visibilidad/tamaño, y quedarnos con el
    // que es realmente interactuable (visible, habilitado, área > 0) en
    // vez de con el primero del documento. Ahora sí.
    //
    // Si el log muestra "candidatos: 1" y ese único candidato ya sale
    // como no visible/no habilitado, esto confirma que el problema NO es
    // de selección entre duplicados sino que ese input está genuinamente
    // bloqueado (por ejemplo tapado por otra capa) -- haría falta mirar
    // el HTML de evidencia (target/fallo-*.html) para ver qué lo tapa.
    // -----------------------------------------------------------------
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

    // -----------------------------------------------------------------
    // FIX (7ma vuelta - hallazgo clave): el log mostró que para los
    // campos de LOGIN hay un ÚNICO candidato (no un duplicado tapando al
    // real, como sospechábamos) y ese único candidato reporta
    // enabled=false de forma 100% consistente en todas las corridas. En
    // REGISTRO, el mismo tipo de campo reporta enabled=true. enabled=false
    // en Selenium refleja el atributo HTML "disabled" real -- ningún
    // click ni tecla real puede escribir ahí, sin importar la técnica.
    // Esto deja de ser un problema de Selenium: apunta a que el propio
    // widget Flutter (TextField) de la pantalla de login tiene
    // `enabled: false` (o un AbsorbPointer/IgnorePointer encima) atado a
    // alguna condición que nunca se cumple -- por ejemplo un flag de
    // "cargando sesión" que nunca pasa a false. Esto habría que
    // verificarlo también escribiendo a mano en un Chrome normal.
    //
    // Este método sondea el campo durante un tiempo acotado esperando
    // que se habilite. Si nunca se habilita, el test falla de inmediato
    // con un mensaje explícito señalando la causa probable, en vez de
    // recién fallar minutos después con el mensaje genérico de "Aviso".
    // -----------------------------------------------------------------
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
                // el DOM puede estar en medio de una reconstrucción; reintentamos
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

    // -----------------------------------------------------------------
    // FIX (2da vuelta): el XPath del botón buscaba "Iniciar Sesión" y
    // podía matchear MÁS DE UN elemento <flt-semantics> (por ejemplo, uno
    // visualmente correcto y otro invisible/superpuesto de otra capa de
    // semántica). driver.findElement() se queda con el primero en orden
    // de documento, que no necesariamente es el clickeable real -- así
    // el test "hacía click" sin que pasara nada (ni navegación ni error).
    //
    // FIX (3ra vuelta): con `contains(., texto)` sobre <flt-semantics>
    // matcheábamos también los nodos CONTENEDORES/ancestros del botón
    // real, porque en el árbol de semántica de Flutter un nodo padre
    // "contiene" el texto de todos sus hijos. Por eso salían 8
    // candidatos: probablemente una cadena de contenedores anidados, no
    // 8 botones distintos. driver.findElements() los devuelve en orden
    // de documento, así que nos quedábamos con el contenedor MÁS
    // EXTERNO (el primero), no con el botón real (el más específico/
    // anidado). Ahora:
    //   1) Preferimos candidatos con @role='button' explícito.
    //   2) Entre los candidatos válidos, elegimos el de MENOR ÁREA
    //      (ancho x alto) -- un contenedor que envuelve todo el
    //      formulario siempre va a ser mucho más grande que el botón en
    //      sí, así que el de área más chica (pero > 0) es casi siempre
    //      el nodo específico que corresponde al botón real.
    //   3) Se loguean coordenadas y tamaño reales (no el toString por
    //      defecto de Rectangle, que no es legible).
    // -----------------------------------------------------------------
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

                // Preferimos fuertemente los que tengan role="button"
                // explícito: si ya encontramos uno con ese role, solo
                // reemplazamos "mejor" por otro candidato con role=button
                // de área aún menor (nunca por uno sin ese role).
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
            // Ninguno cumplió los criterios ideales; devolvemos el
            // primero como último recurso.
            return todos.get(0);
        }

        return mejor;
    }

    // -----------------------------------------------------------------
    // FIX (13va vuelta): las verificaciones de SEL-03 (el reporte recién
    // subido aparece en la lista) y SEL-04 (mensaje de "no hay reportes"
    // o la lista) buscaban por contains(@aria-label, '...'), pero ese
    // texto vive como CONTENIDO dentro de un <span> hijo de
    // <flt-semantics>, no como atributo aria-label -- por eso nunca
    // aparecía sin importar cuánto se esperara. Este helper generaliza
    // el mismo patrón que ya usa encontrarBotonPorTexto() (buscar por
    // contains(., 'texto') = contenido de texto, no atributo, y quedarse
    // con el candidato visible de MENOR área para no atrapar un
    // contenedor gigante que también "contiene" el texto), pero en un
    // loop con timeout propio, porque acá no estamos clickeando sino
    // esperando a que algo aparezca en pantalla.
    //
    // Acepta uno o más textos posibles (unidos con OR) para los casos
    // donde cualquiera de varios mensajes es un resultado válido (por
    // ejemplo: "Todavía no hay reportes." O cualquier item de reporte).
    // Devuelve null si ninguno aparece dentro del timeout, en vez de
    // lanzar una excepción, para que el test pueda decidir cómo fallar
    // (con evidencia, mensaje claro, etc.).
    // -----------------------------------------------------------------
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
                    // el nodo puede haber quedado stale por una
                    // reconstrucción del árbol de semántica justo ahora;
                    // seguimos probando en la próxima vuelta del loop.
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

    // -----------------------------------------------------------------
    // FIX (2da vuelta): intenta un click normal de Selenium; si por
    // cualquier motivo no lo puede ejecutar (elemento tapado por otra
    // capa semántica, coordenadas fuera de vista, etc.), cae a un click
    // disparado directamente por JavaScript, que ignora esas
    // restricciones de "actionability" del navegador.
    // -----------------------------------------------------------------
    private void clickForzado(WebElement el) {
        try {
            el.click();
        } catch (Exception e) {
            System.out.println(">>> DEBUG click normal falló (" + e.getMessage() + "), probando click por JS");
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
        }
    }

    // -----------------------------------------------------------------
    // FIX (2da vuelta): antes solo buscábamos avisos/errores con
    // palabras específicas ("Aviso", "incorrect", "contraseña", etc.),
    // lo cual fallaba en silencio si el diálogo real tenía otro texto o
    // estructura. Esta utilidad busca de forma genérica cualquier
    // elemento con role de diálogo/alerta, y si no encuentra nada,
    // vuelca al log un fragmento del HTML de la página para diagnóstico
    // manual.
    // -----------------------------------------------------------------
    // FIX (3ra vuelta - falso positivo): el fallback por palabras sueltas
    // buscaba `contains(@aria-label,'contraseña')`, y eso matchea el
    // PROPIO <input> del campo de contraseña (su aria-label siempre es
    // "Ingresa tu contraseña"), no un diálogo de error real. Por eso el
    // log anterior mostró "Aviso visible: [Ingresa tu contraseña]" en
    // TODOS los casos, incluso cuando no había ningún error -- era el
    // campo del formulario, no un aviso. Ahora excluimos explícitamente
    // los <input>/<textarea> y cualquier aria-label que empiece con
    // "Ingresa tu" (el patrón de los hints de los campos).
    // -----------------------------------------------------------------
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

        // Fallback: buscar por palabras sueltas conocidas, EXCLUYENDO los
        // propios campos de texto del formulario (inputs/textareas) y
        // cualquier aria-label que sea el hint de un campo ("Ingresa
        // tu..."), para no confundir un campo del formulario con un
        // aviso de error real.
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

    // -----------------------------------------------------------------
    // Utilidad: hace login y espera a que la pantalla de Inicio cargue
    // -----------------------------------------------------------------
    // -----------------------------------------------------------------
    // FIX (8va vuelta): con el bug de accesibilidad de login.dart ya
    // corregido, los campos son de verdad interactivos -- y eso significa
    // que ESCRIBIR en ellos ahora dispara reconstrucciones reales del
    // árbol de semántica de Flutter (algo que antes, con el campo
    // deshabilitado, nunca pasaba). Si releemos el <input> justo en medio
    // de una de esas reconstrucciones, momentáneamente no hay ningún nodo
    // con ese aria-label y buscarInputFresco() tira NoSuchElementException.
    // Esta variante es para relecturas que son solo diagnósticas (loguear
    // el valor, chequear enabled) y NO deben frenar el test si fallan:
    // reintenta un puñado de veces con una pausa corta, y si nunca
    // encuentra nada, devuelve null en vez de propagar la excepción.
    // -----------------------------------------------------------------
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

    private void login(String email, String password) {
        // BASE_URL solo (sin hash) carga la landing page pública, que NO
        // tiene el formulario de login: solo un botón "Iniciar Sesión"
        // arriba a la derecha. Navegamos directo a la ruta del login
        // (igual que el registro ya usa "/#/register" con éxito).
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

        // FIX (7ma vuelta): antes de intentar escribir, sondeamos si el
        // campo llega a habilitarse. Si nunca lo hace, es más útil fallar
        // acá mismo con un mensaje que señale la causa probable (bug de
        // la app) que dejar que el flujo siga y falle recién en el
        // "Aviso" final con un mensaje genérico.
        esperarCampoHabilitado("Ingresa tu correo", 20);

        // Escribe el correo primero. escribirEnCampo() ya incluye la
        // pausa de sincronización con Flutter y la verificación final.
        escribirEnCampo("Ingresa tu correo", email);

        esperarCampoHabilitado("Ingresa tu contraseña", 5);

        // FIX: ya no hace falta buscar una referencia "fresca" a mano --
        // escribirEnCampo() ahora resuelve el elemento internamente por
        // aria-label en cada intento, así que siempre parte de cero.
        escribirEnCampo("Ingresa tu contraseña", password);

        // FIX: releemos AMBOS campos ya "frescos" (recién buscados) justo
        // antes del click, para loguear el valor real que Flutter va a
        // ver -- no el de las referencias originales, que pueden haber
        // quedado desconectadas del TextEditingController tras el cambio
        // de foco.
        // FIX (8va vuelta): esta relectura es solo diagnóstica (loguear
        // el valor y chequear enabled antes del click) -- usamos la
        // variante que reintenta y no revienta el test si el árbol de
        // semántica está en medio de una reconstrucción justo ahora.
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

            // FIX (7ma vuelta): si a esta altura los campos siguen
            // deshabilitados, no tiene sentido seguir -- el click en el
            // botón va a fallar con el mismo "Aviso" de siempre, pero el
            // mensaje no deja en claro la causa real. Cortamos acá con un
            // mensaje que sí la deja en claro: esto no es un problema de
            // Selenium, es que el TextField de Flutter nunca se habilitó.
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

        // FIX (2da vuelta): usamos encontrarBotonPorTexto (que loguea
        // cuántos candidatos matchean y elige el visible/clickeable) en
        // vez de findElement directo, que se quedaba con el primero del
        // documento aunque no fuera el real. clickForzado() además cae a
        // un click por JS si el nativo no surte efecto.
        WebElement botonLogin = encontrarBotonPorTexto("Iniciar Sesión");
        clickForzado(botonLogin);

        // Espera a que desaparezca la pantalla de login (cambia la ruta).
        // Se usa un timeout más largo que el resto de las esperas porque
        // el primer login contra Firebase Auth en un hosting frío puede
        // tardar más que 15s.
        boolean navego = false;
        try {
            new WebDriverWait(driver, Duration.ofSeconds(25))
                    .until(d -> !d.getCurrentUrl().toLowerCase().contains("login"));
            navego = true;
        } catch (TimeoutException ignored) {
            // seguimos abajo para diagnosticar por qué no navegó
        }

        if (!navego) {
            // FIX: detección genérica de diálogos, ya sin falsos positivos
            // por los propios campos del formulario.
            String textoAviso = detectarAvisoVisible();

            // Chequeo extra de diagnóstico: buscamos la palabra "Aviso"
            // en el HTML crudo de la página, para saber con certeza si
            // hubo o no algún diálogo, más allá de si nuestros XPath lo
            // detectan bien o no.
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

    // ===================================================================
    // SEL-01: Login exitoso con correo y contraseña válidos
    // ===================================================================
    @Test
    @DisplayName("SEL-01: Login con credenciales válidas")
    void testLoginExitoso() {
        login(USER_EMAIL, USER_PASSWORD);
        assertFalse(driver.getCurrentUrl().toLowerCase().contains("login"),
                "Después de un login válido no debería seguir en /login");
    }

    // ===================================================================
    // SEL-01b: Login fallido con contraseña incorrecta (caso negativo)
    // ===================================================================
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

        // Debe seguir en login y mostrar algún mensaje de error (diálogo
        // o SnackBar). FIX: en vez de esperar un locator específico que
        // puede no matchear la estructura real del diálogo, sondeamos con
        // detectarAvisoVisible() en un bucle corto hasta encontrar algo o
        // agotar el tiempo -- y si se agota, mostramos evidencia clara.
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

    // ===================================================================
    // SEL-02: Registro de un nuevo usuario (sin captura de rostro)
    // ===================================================================
    // NOTA: El registro real exige "Capturar Rostro" con la webcam antes de
    // habilitar "Registrar Usuario" (ver register.dart). Selenium no puede
    // simular una cámara real fácilmente, así que este test solo valida que
    // el formulario recibe los datos de texto correctamente; la captura de
    // rostro queda fuera de alcance de esta prueba automatizada y debe
    // probarse manualmente (documentarlo así en el Reporte de Hallazgos).
    @Test
    @DisplayName("SEL-02: Completa el formulario de registro (texto)")
    void testCompletarFormularioRegistro() {
        driver.get(BASE_URL + "/#/register");
        enableFlutterAccessibility();

        wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//input[@aria-label='Nombre']")));
        escribirEnCampo("Nombre", "Usuario Selenium");

        // FIX: cada campo se resuelve internamente por aria-label dentro
        // de escribirEnCampo(), en vez de tomar todas las referencias de
        // una sola vez al principio.
        String correoPrueba = "selenium_" + System.currentTimeMillis() + "@test.com";
        escribirEnCampo("Correo", correoPrueba);
        escribirEnCampo("Contraseña", "Clave123!");
        escribirEnCampo("Teléfono", "3001234567");

        // Releemos los campos frescos para verificar el valor final real.
        WebElement campoNombreFresco = buscarInputFresco("Nombre");
        WebElement campoCorreoFresco = buscarInputFresco("Correo");

        assertEquals("Usuario Selenium", campoNombreFresco.getAttribute("value"));
        assertEquals(correoPrueba, campoCorreoFresco.getAttribute("value"));

        // El botón "Registrar Usuario" queda deshabilitado hasta capturar
        // rostro con la cámara, por eso no se hace click acá.
    }

    // ===================================================================
    // SEL-03: Un usuario "jefe" sube un reporte y lo ve en la lista
    // ===================================================================
    @Test
    @DisplayName("SEL-03: Jefe sube un reporte y aparece en 'Reportes recientes'")
    void testJefeSubeReporte() {
        login(JEFE_EMAIL, JEFE_PASSWORD);

        // FIX (9na vuelta): driver.get() es una recarga completa -- Flutter
        // arranca de cero y el árbol de semántica hay que reactivarlo de
        // nuevo, o ningún aria-label de esta pantalla va a aparecer.
        driver.get(BASE_URL + "/#/reportes");
        enableFlutterAccessibility();

        String textoReporte = "Reporte de prueba Selenium " + System.currentTimeMillis();

        // FIX (12va vuelta): /reportes probablemente dependa de una consulta
        // a Firestore (traer la lista de usuarios para el dropdown), que en
        // un hosting frío puede tardar más de los 15s que usa `wait` -- el
        // mismo motivo por el que login() ya usa 25s en vez de 15s. Subimos
        // a 30s acá y, si de nuevo no aparece, guardamos captura+HTML para
        // poder ver la causa real (spinner que nunca termina, aria-label
        // distinto al esperado, etc.) en vez de quedarnos solo con el stack
        // trace genérico de TimeoutException.
        WebElement dropdownUsuario;
        try {
            dropdownUsuario = new WebDriverWait(driver, Duration.ofSeconds(30))
                    .until(ExpectedConditions.visibilityOfElementLocated(
                            By.xpath("//*[@aria-label='Selecciona usuario']")));
        } catch (TimeoutException e) {
            capturarEvidencia("fallo-SEL03-dropdownUsuario-" + System.currentTimeMillis());
            throw e;
        }
        dropdownUsuario.click();

        // Selecciona la primera opción disponible del dropdown
        WebElement primeraOpcion = wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("(//flt-semantics[@role='option'])[1]")));
        primeraOpcion.click();

        escribirEnCampo("Escribe tu reporte aquí...", textoReporte);

        WebElement botonSubir = driver.findElement(
                By.xpath("//flt-semantics[contains(., 'Subir Reporte')]"));
        botonSubir.click();

        // FIX (13va vuelta): igual que en SEL-04, el texto del reporte
        // recién subido no queda en un aria-label sino como contenido de
        // texto dentro del <flt-semantics> de ese ítem de la lista.
        // Buscarlo por contains(@aria-label, ...) nunca iba a encontrar
        // nada. Se reemplaza por esperarElementoConTexto(), que busca por
        // contenido (contains(., ...)) y elige el candidato visible de
        // menor área.
        WebElement reporteEnLista = esperarElementoConTexto(30, textoReporte);
        if (reporteEnLista == null) {
            String nombreEvidencia = "fallo-SEL03-reporteEnLista-" + System.currentTimeMillis();
            capturarEvidencia(nombreEvidencia);
            fail("El reporte recién subido ('" + textoReporte + "') no apareció en la lista tras 30s. "
                    + "Revisar target/" + nombreEvidencia + ".html para ver el DOM completo en ese instante.");
        }
        assertTrue(reporteEnLista.isDisplayed());
    }

    // ===================================================================
    // SEL-04: Un usuario NO-jefe entra a Reportes y ve la lista (o el
    // mensaje de "Todavía no hay reportes."), nunca un loader infinito.
    // ===================================================================
    @Test
    @DisplayName("SEL-04: Usuario no-jefe ve sus reportes sin loader infinito")
    void testUsuarioNoJefeVeReportes() {
        login(USER_EMAIL, USER_PASSWORD);

        // FIX (9na vuelta): mismo motivo que en SEL-03 -- sin esto, el
        // árbol de semántica de la nueva carga de /reportes nunca queda
        // activo y wait.until(...) termina en TimeoutException porque
        // ningún aria-label llega a existir en el DOM.
        driver.get(BASE_URL + "/#/reportes");
        enableFlutterAccessibility();

        // FIX (13va vuelta - causa raíz real, no era timing): el HTML de
        // evidencia mostró que "Todavía no hay reportes." (y el texto de
        // cada ítem de reporte) vive como CONTENIDO de un <span> dentro
        // de <flt-semantics>, no como atributo aria-label -- por eso
        // contains(@aria-label, ...) nunca lo encontraba, sin importar
        // cuánto se esperara. Se reemplaza por esperarElementoConTexto(),
        // que busca por contenido de texto (mismo patrón que ya usan
        // encontrarBotonPorTexto()/detectarAvisoVisible()) y devuelve el
        // candidato visible de menor área, evitando quedarnos con un
        // contenedor gigante que también "contiene" el texto.
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