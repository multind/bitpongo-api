package com.multind.bitpongo.notification;

import java.util.Locale;
import java.util.Map;

/** Additional locales shared by event and device-test notification renderers. */
final class NotificationLocaleCopy {
    private static final Map<String, NotificationLocaleCopy> LOCALES = Map.ofEntries(
        entry("es-MX", "Falla crítica del programador|La orden requiere revisión manual|La operación falló|Datos de mercado no disponibles|Se omitió la ejecución del plan|Ejecución del plan retrasada|Operación exitosa|No se pudo registrar el valor de los activos|Sistema restablecido|Servicio iniciado|Prueba de Bark|Alerta de precio|Alerta de webhook|Señal de estrategia",
            "Hora|Hora programada|Hora de ejecución|Retraso|ID de usuario|ID del plan|ID de la intención de orden|Símbolo|Resultado|Error",
            "Prueba de notificación|Las notificaciones de Bitpongo están listas.|El precio no es inferior al precio promedio de la posición; no se realizó la compra.|El precio está fuera del rango de compra; no se realizó la compra."),
        entry("es-ES", "Fallo crítico del programador|La orden requiere revisión manual|La operación ha fallado|Datos de mercado no disponibles|Se ha omitido la ejecución del plan|Ejecución del plan retrasada|Operación completada|No se ha podido registrar el valor de los activos|Sistema restablecido|Servicio iniciado|Prueba de Bark|Alerta de precio|Alerta de webhook|Señal de estrategia",
            "Hora|Hora programada|Hora de ejecución|Retraso|ID de usuario|ID del plan|ID de la intención de orden|Símbolo|Resultado|Error",
            "Prueba de notificación|Las notificaciones de Bitpongo están listas.|El precio no es inferior al precio medio de la posición; no se ha efectuado la compra.|El precio está fuera del intervalo de compra; no se ha efectuado la compra."),
        entry("pt-BR", "Falha crítica no agendador|A ordem precisa de revisão manual|Falha na negociação|Dados de mercado indisponíveis|Execução do plano ignorada|Execução do plano atrasada|Negociação concluída|Falha no registro do valor dos ativos|Sistema restabelecido|Serviço iniciado|Teste do Bark|Alerta de preço|Alerta de webhook|Sinal da estratégia",
            "Horário|Horário agendado|Horário de execução|Atraso|ID do usuário|ID do plano|ID da intenção de ordem|Símbolo|Resultado|Erro",
            "Teste de notificação|As notificações do Bitpongo estão prontas.|O preço não está abaixo do preço médio da posição; nenhuma compra foi feita.|O preço está fora da faixa de compra; nenhuma compra foi feita."),
        entry("ja", "スケジューラーの重大な障害|注文の手動確認が必要です|取引に失敗しました|市場データを取得できません|プランの実行をスキップしました|プランの実行が遅れています|取引が完了しました|資産評価額の記録に失敗しました|システムが復旧しました|サービスを開始しました|Bark テスト|価格アラート|Webhook アラート|戦略シグナル",
            "時刻|予定時刻|実行時刻|遅延|ユーザー ID|プラン ID|注文インテント ID|銘柄|結果|エラー",
            "通知テスト|Bitpongo の通知を受信できます。|現在価格が保有分の平均取得価格を下回っていないため、購入しませんでした。|現在価格が購入価格の範囲外のため、購入しませんでした。"),
        entry("ko", "스케줄러에 심각한 오류 발생|주문을 수동으로 검토해야 합니다|거래 실패|시장 데이터를 사용할 수 없음|계획 실행을 건너뜀|계획 실행 지연|거래 완료|자산 가치 기록 실패|시스템 복구 완료|서비스 시작|Bark 테스트|가격 알림|웹훅 알림|전략 신호",
            "시간|예정 시간|실행 시간|지연|사용자 ID|계획 ID|주문 의도 ID|거래 종목|결과|오류",
            "알림 테스트|Bitpongo 알림을 받을 수 있습니다.|현재 가격이 보유 자산의 평균 매입가보다 낮지 않아 매수하지 않았습니다.|현재 가격이 매수 범위를 벗어나 매수하지 않았습니다."),
        entry("id", "Kegagalan kritis penjadwal|Pesanan perlu diperiksa secara manual|Transaksi gagal|Data pasar tidak tersedia|Eksekusi rencana dilewati|Eksekusi rencana tertunda|Transaksi berhasil|Gagal mencatat nilai aset|Sistem pulih|Layanan dimulai|Uji Bark|Peringatan harga|Peringatan webhook|Sinyal strategi",
            "Waktu|Waktu terjadwal|Waktu eksekusi|Keterlambatan|ID pengguna|ID rencana|ID niat pesanan|Simbol|Hasil|Kesalahan",
            "Uji notifikasi|Notifikasi Bitpongo siap digunakan.|Harga saat ini tidak lebih rendah dari harga rata-rata posisi; pembelian tidak dilakukan.|Harga berada di luar rentang pembelian; pembelian tidak dilakukan."),
        entry("th", "ตัวจัดกำหนดการขัดข้องร้ายแรง|ต้องตรวจสอบคำสั่งซื้อขายด้วยตนเอง|การซื้อขายล้มเหลว|ไม่มีข้อมูลตลาด|ข้ามการดำเนินการตามแผน|การดำเนินการตามแผนล่าช้า|การซื้อขายสำเร็จ|บันทึกมูลค่าสินทรัพย์ไม่สำเร็จ|ระบบกลับมาทำงานแล้ว|เริ่มบริการแล้ว|ทดสอบ Bark|การแจ้งเตือนราคา|การแจ้งเตือนเว็บฮุก|สัญญาณกลยุทธ์",
            "เวลา|เวลาที่กำหนด|เวลาดำเนินการ|ความล่าช้า|รหัสผู้ใช้|รหัสแผน|รหัสเจตนาส่งคำสั่ง|สัญลักษณ์|ผลลัพธ์|ข้อผิดพลาด",
            "ทดสอบการแจ้งเตือน|การแจ้งเตือนของ Bitpongo พร้อมใช้งานแล้ว|ราคาปัจจุบันไม่ต่ำกว่าราคาซื้อเฉลี่ยของสินทรัพย์ที่ถืออยู่ จึงไม่ได้ซื้อ|ราคาอยู่นอกช่วงราคาซื้อ จึงไม่ได้ซื้อ"),
        entry("vi", "Lỗi nghiêm trọng của bộ lập lịch|Lệnh cần được xem xét thủ công|Giao dịch thất bại|Không có dữ liệu thị trường|Đã bỏ qua việc thực hiện kế hoạch|Thực hiện kế hoạch bị chậm|Giao dịch thành công|Không ghi được giá trị tài sản|Hệ thống đã phục hồi|Dịch vụ đã khởi động|Kiểm tra Bark|Cảnh báo giá|Cảnh báo webhook|Tín hiệu chiến lược",
            "Thời gian|Thời gian dự kiến|Thời gian thực hiện|Độ trễ|ID người dùng|ID kế hoạch|ID ý định đặt lệnh|Mã giao dịch|Kết quả|Lỗi",
            "Kiểm tra thông báo|Thông báo Bitpongo đã sẵn sàng.|Giá hiện tại không thấp hơn giá vốn trung bình của vị thế nên không thực hiện mua.|Giá nằm ngoài khoảng mua nên không thực hiện mua."),
        entry("ar", "عطل حرج في برنامج الجدولة|يتطلب الأمر مراجعة يدوية|فشلت الصفقة|بيانات السوق غير متاحة|تم تخطي تنفيذ الخطة|تأخر تنفيذ الخطة|تمت الصفقة بنجاح|تعذّر تسجيل قيمة الأصول|استعاد النظام عمله|بدأت الخدمة|اختبار Bark|تنبيه السعر|تنبيه Webhook|إشارة الاستراتيجية",
            "الوقت|الوقت المجدول|وقت التنفيذ|التأخير|معرّف المستخدم|معرّف الخطة|معرّف نية تقديم الأمر|الرمز|النتيجة|الخطأ",
            "اختبار الإشعارات|إشعارات Bitpongo جاهزة.|السعر الحالي ليس أقل من متوسط سعر المركز، لذا لم يتم الشراء.|السعر خارج نطاق الشراء، لذا لم يتم الشراء."),
        entry("fr", "Défaillance critique du planificateur|L’ordre nécessite une vérification manuelle|Échec de la transaction|Données de marché indisponibles|Exécution du plan ignorée|Exécution du plan retardée|Transaction réussie|Échec de l’enregistrement de la valeur des actifs|Système rétabli|Service démarré|Test Bark|Alerte de prix|Alerte webhook|Signal de stratégie",
            "Heure|Heure prévue|Heure d’exécution|Retard|ID utilisateur|ID du plan|ID de l’intention d’ordre|Symbole|Résultat|Erreur",
            "Test de notification|Les notifications Bitpongo sont prêtes.|Le prix actuel n’est pas inférieur au prix moyen de la position : aucun achat effectué.|Le prix est hors de la plage d’achat : aucun achat effectué."),
        entry("de", "Schwerwiegender Fehler im Aufgabenplaner|Order muss manuell geprüft werden|Handel fehlgeschlagen|Marktdaten nicht verfügbar|Planausführung übersprungen|Planausführung verzögert|Handel erfolgreich|Aufzeichnung des Vermögenswerts fehlgeschlagen|System wieder verfügbar|Dienst gestartet|Bark-Test|Preisalarm|Webhook-Alarm|Strategiesignal",
            "Zeit|Geplante Zeit|Ausführungszeit|Verzögerung|Benutzer-ID|Plan-ID|Orderabsichts-ID|Symbol|Ergebnis|Fehler",
            "Benachrichtigungstest|Bitpongo-Benachrichtigungen sind bereit.|Der aktuelle Preis liegt nicht unter dem Durchschnittspreis der Position. Es wurde kein Kauf ausgeführt.|Der Preis liegt außerhalb der Kaufpreisspanne. Es wurde kein Kauf ausgeführt."),
        entry("it", "Errore critico del pianificatore|L’ordine richiede una verifica manuale|Operazione non riuscita|Dati di mercato non disponibili|Esecuzione del piano saltata|Esecuzione del piano in ritardo|Operazione riuscita|Impossibile registrare il valore degli asset|Sistema ripristinato|Servizio avviato|Test Bark|Avviso di prezzo|Avviso webhook|Segnale della strategia",
            "Ora|Ora pianificata|Ora di esecuzione|Ritardo|ID utente|ID del piano|ID dell’intento d’ordine|Simbolo|Risultato|Errore",
            "Test di notifica|Le notifiche di Bitpongo sono pronte.|Il prezzo attuale non è inferiore al prezzo medio della posizione; nessun acquisto effettuato.|Il prezzo è fuori dall’intervallo di acquisto; nessun acquisto effettuato.")
    );

    private final String tag;
    private final String[] titles;
    private final String[] labels;
    private final String[] extra;

    private NotificationLocaleCopy(String tag, String titles, String labels, String extra) {
        this.tag = tag;
        this.titles = titles.split("\\|", -1);
        this.labels = labels.split("\\|", -1);
        this.extra = extra.split("\\|", -1);
    }

    private static Map.Entry<String, NotificationLocaleCopy> entry(String tag, String titles, String labels, String extra) {
        return Map.entry(tag.toLowerCase(Locale.ROOT), new NotificationLocaleCopy(tag, titles, labels, extra));
    }

    static NotificationLocaleCopy find(String locale) {
        return locale == null ? null : LOCALES.get(locale.toLowerCase(Locale.ROOT));
    }

    String tag() { return tag; }
    String title(NotificationEventType event) {
        return titles[switch (event) {
            case SCHEDULER_FATAL -> 0;
            case ORDER_MANUAL_REVIEW -> 1;
            case TRADE_FAILED -> 2;
            case MARKET_OUTAGE -> 3;
            case PLAN_EXECUTION_SKIPPED -> 4;
            case PLAN_EXECUTION_DELAYED -> 5;
            case TRADE_SUCCEEDED -> 6;
            case ASSET_SNAPSHOT_FAILED -> 7;
            case SYSTEM_RECOVERED -> 8;
            case SERVICE_STARTED -> 9;
            case BARK_TEST -> 10;
            case PRICE_ALERT_TRIGGERED -> 11;
            case WEBHOOK_TRIGGERED -> 12;
            case STRATEGY_SIGNAL -> 13;
        }];
    }
    String label(int index) { return labels[index] + ": "; }
    String testTitle() { return extra[0]; }
    String testBody() { return extra[1]; }
    String result(Object value) {
        if ("PRICE_NOT_BELOW_AVERAGE".equals(value)) return extra[2];
        if ("PRICE_OUT_OF_RANGE".equals(value)) return extra[3];
        return value == null ? null : String.valueOf(value);
    }
}
