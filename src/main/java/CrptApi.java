/*

    Класс thread-safe:
        - Все поля final
        - Все поля инициализируются в конструкторе и не меняются внутри методов
        - В конструкторе не раскрывается ссылка this

    Библиотеки:
        - HTTP клиент: OkHttp
        - JSON сериализация: Gson

    Базовые классы вышли довольно массивными, поскольку
    нельзя было использовать сторонних библиотек помимо HTTP и JSON
    Однако все же опущены геттеры для классов, используемых в передаче
    Gson, в свою очередь, берет их значения при помощи механизма рефлексии

    Также у классов, используемых в передаче, все поля объявлены final
    Причина тому - good practice при сериализации/десериализации

    ** Идея реализации - использование семафора
        Ограничение вызовов похоже на рассадку зрителей в зале, наш семафор в роли билетера
        У нас есть время выступления (timeUnit), за это время в зал можно пустить только
        определенное количество людей (requestLimit). Зрители приходят, а билетер раздает
        билеты. Когда билеты заканчиваются - билетер не пускает никого более, а зрители
        досматривают представление. После все билеты возвращаются билетеру, и он может
        продолжить свою работу.

 */

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import okhttp3.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    private final Semaphore semaphore;
    private final Gson gson;
    private final OkHttpClient httpClient;

    static class GoodsTurnoverDocument {

        @SerializedName("description")
        private final Description description;

        @SerializedName("doc_id")
        private final String docId;

        @SerializedName("doc_status")
        private final String docStatus;

        @SerializedName("doc_type")
        private final String docType = "LP_INTRODUCE_GOODS";

        @SerializedName("importRequest")
        private final boolean importRequest;

        @SerializedName("owner_inn")
        private final String ownerInn;

        @SerializedName("participant_inn")
        private final String participantInn;

        @SerializedName("producer_inn")
        private final String producerInn;

        @SerializedName("production_date")
        private final LocalDate productionDate;

        @SerializedName("production_type")
        private final String productionType;

        @SerializedName("products")
        private final List<Product> products;

        @SerializedName("reg_date")
        private final LocalDate regDate;

        @SerializedName("reg_number")
        private final String regNumber;

        public GoodsTurnoverDocument(Description description, String docId, String docStatus,
                                     boolean importRequest, String ownerInn, String participantInn,
                                     String producerInn, LocalDate productionDate, String productionType,
                                     List<Product> products, LocalDate regDate, String regNumber) {
            this.description = description;
            this.docId = docId;
            this.docStatus = docStatus;
            this.importRequest = importRequest;
            this.ownerInn = ownerInn;
            this.participantInn = participantInn;
            this.producerInn = producerInn;
            this.productionDate = productionDate;
            this.productionType = productionType;
            this.products = products;
            this.regDate = regDate;
            this.regNumber = regNumber;
        }

    }


    static class Description {

        @SerializedName("participantInn")
        private final String participantInn;

        public Description(String participantInn) {
            this.participantInn = participantInn;
        }

        public String getParticipantInn() {
            return participantInn;
        }

    }


    static class Product {

        @SerializedName("certificate_document")
        private final String certificateDocument;

        @SerializedName("certificate_document_date")
        private final LocalDate certificateDocumentDate;

        @SerializedName("certificate_document_number")
        private final String certificateDocumentNumber;

        @SerializedName("owner_inn")
        private final String ownerInn;

        @SerializedName("producer_inn")
        private final String producerInn;

        @SerializedName("production_date")
        private final LocalDate productionDate;

        @SerializedName("tnved_code")
        private final String tnvedCode;

        @SerializedName("uit_code")
        private final String uitCode;

        @SerializedName("uitu_code")
        private final String uituCode;

        public Product(String certificateDocument, LocalDate certificateDocumentDate,
                       String certificateDocumentNumber, String ownerInn, String producerInn,
                       LocalDate productionDate, String tnvedCode, String uitCode, String uituCode) {
            this.certificateDocument = certificateDocument;
            this.certificateDocumentDate = certificateDocumentDate;
            this.certificateDocumentNumber = certificateDocumentNumber;
            this.ownerInn = ownerInn;
            this.producerInn = producerInn;
            this.productionDate = productionDate;
            this.tnvedCode = tnvedCode;
            this.uitCode = uitCode;
            this.uituCode = uituCode;
        }

    }

    enum ApiSource {
        CHESTNY_ZNAK_GOODS_TURNOVER("https://ismp.crpt.ru/api/v3/lk/documents/create")
        ;

        private final String url;

        ApiSource(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }

    static class LocalDateTypeAdapter implements JsonSerializer<LocalDate>, JsonDeserializer<LocalDate> {

        private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        @Override
        public JsonElement serialize(final LocalDate date, final Type typeOfSrc,
                                     final JsonSerializationContext context) {
            return new JsonPrimitive(date.format(formatter));
        }

        @Override
        public LocalDate deserialize(final JsonElement json, final Type typeOfT,
                                     final JsonDeserializationContext context) throws JsonParseException {
            return LocalDate.parse(json.getAsString(), formatter);
        }
    }

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.semaphore = new Semaphore(requestLimit);

        GsonBuilder builder = new GsonBuilder()
            .registerTypeAdapter(LocalDate.class, new LocalDateTypeAdapter())
            .setPrettyPrinting();

        this.gson = builder.create();
        this.httpClient = new OkHttpClient();

        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(timeUnit.toMillis(1));
                    semaphore.release(requestLimit - semaphore.availablePermits()); // возвращаем все взятые билеты
                } catch (InterruptedException e) {
                    System.err.println("Поток был прерван");
                }
            }
        }).start();
    }

    public void createDocument(GoodsTurnoverDocument doc, String signature) {
        try {
            semaphore.acquire(); // выдаем билет

            MediaType JSON = MediaType.parse("application/json; charset=utf-8");
            String param = gson.toJson(doc);

            System.out.println("** Переданный JSON документ");
            System.out.println(param);
            System.out.println();

            // Создаем тело запроса + записываем подпись в заголовки
            RequestBody requestBody = RequestBody.create(param, JSON);
            Request request = new Request.Builder()
                .url(ApiSource.CHESTNY_ZNAK_GOODS_TURNOVER.getUrl())
                .post(requestBody)
                .header("Signature", signature)
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    System.out.println("Результат запроса: " + responseBody);
                } else {
                    System.err.println("Ошибка при запросе [код: " + response.code() + "]. " + response.message());
                }
            }
        } catch (IOException | InterruptedException exc) {
            exc.printStackTrace();
        }
    }

    public static void main(String[] args) {
        CrptApi apiClient = new CrptApi(TimeUnit.MINUTES, 15);

        List<Product> products = List.of(
            new Product("string", LocalDate.now(),
                        "string", "string",
                        "string", LocalDate.now(), "string",
                        "string", "string")
        );

        Description description = new Description("string");

        GoodsTurnoverDocument document = new GoodsTurnoverDocument(
            description,
            "string",
            "string",
            true,
            "string",
            "string",
            "string",
            LocalDate.now(),
            "string",
            products,
            LocalDate.now(),
            "string"
        );

        apiClient.createDocument(document, "xxxx-xxxx-xxxx-xxxx");
    }

}
