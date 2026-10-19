
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

class MedicalAppointmentSystem extends JFrame {

    private final UserSession currentUser;

    private final JTextField nameField = new JTextField();
    private final JTextField ageField = new JTextField();
    private final JTextField phoneField = new JTextField();
    private final JTextField dateField = new JTextField(
            LocalDate.now().plusDays(1).toString());

    private final JTextArea symptomsArea = new JTextArea(3, 20);

    private final JComboBox<String> genderBox =
            new JComboBox<>(new String[]{"Female", "Male", "Other"});

    private final JComboBox<String> departmentBox =
            new JComboBox<>(new String[]{
                    "General Medicine", "Cardiology",
                    "Dermatology", "Orthopedics",
                    "Pediatrics", "Dental"
            });

    private final JComboBox<String> doctorBox = new JComboBox<>();

    private final JComboBox<String> timeBox =
            new JComboBox<>(new String[]{
                    "09:00 AM", "10:00 AM", "11:00 AM",
                    "12:00 PM", "02:00 PM", "03:00 PM",
                    "04:00 PM"
            });

    private final DefaultTableModel activeModel = createModel();
    private final DefaultTableModel binModel = createModel();

    private final JTable activeTable = new JTable(activeModel);
    private final JTable binTable = new JTable(binModel);

    private final ArrayList<Appointment> active = new ArrayList<>();
    private final ArrayList<Appointment> bin = new ArrayList<>();

    private final Set<String> bookedSlots = new HashSet<>();

    private final String[] doctors = {
            "Dr. Ananya|General Medicine|500",
            "Dr. Rahul|Cardiology|900",
            "Dr. Priya|Dermatology|700",
            "Dr. Arjun|Orthopedics|800",
            "Dr. Sneha|Pediatrics|600",
            "Dr. Kiran|Dental|400"
    };

    private final DateTimeFormatter timeFormat =
            DateTimeFormatter.ofPattern("hh:mm a");

    private static final int PASSWORD_ITERATIONS = 120000;

    private static class UserSession {
        final long userId;
        final String name;
        final String phone;
        final String role;

        UserSession(long userId, String name, String phone, String role) {
            this.userId = userId;
            this.name = name;
            this.phone = phone;
            this.role = role;
        }

        boolean isStaff() { return "STAFF".equals(role); }
    }

    // Appointment data model
    private static class Appointment {
        String id, name, phone, gender, doctor, department;
        String date, time, symptoms, status;
        Long patientUserId;
        int age, consultationFee, reappointmentFee;

        Appointment(String id, String name, String phone,
                    String gender, int age, String doctor,
                    String department, String date, String time,
                    String symptoms, int consultationFee,
                    int reappointmentFee, String status, Long patientUserId) {

            this.id = id;
            this.name = name;
            this.phone = phone;
            this.gender = gender;
            this.age = age;
            this.doctor = doctor;
            this.department = department;
            this.date = date;
            this.time = time;
            this.symptoms = symptoms;
            this.consultationFee = consultationFee;
            this.reappointmentFee = reappointmentFee;
            this.status = status;
            this.patientUserId = patientUserId;
        }

        int totalFee() {
            return consultationFee + reappointmentFee;
        }

        String slotKey() {
            return doctor + "|" + date + "|" + time;
        }
    }

    private static DefaultTableModel createModel() {
        return new DefaultTableModel(new String[]{
                "Appointment ID", "Patient", "Doctor",
                "Department", "Date", "Time", "Total Fee", "Status"
        }, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
    }

    // Only digits are accepted in numeric fields.
    private void allowDigitsOnly(JTextField field, int maxLength) {
        AbstractDocument doc =
                (AbstractDocument) field.getDocument();

        doc.setDocumentFilter(new DocumentFilter() {

            @Override
            public void insertString(FilterBypass fb, int offset,
                                     String text, AttributeSet attr)
                    throws BadLocationException {

                if (text != null && text.matches("[0-9]+")
                        && fb.getDocument().getLength()
                        + text.length() <= maxLength) {

                    super.insertString(fb, offset, text, attr);
                }
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length,
                                String text, AttributeSet attr)
                    throws BadLocationException {

                if (text == null) {
                    super.replace(fb, offset, length, text, attr);
                    return;
                }

                String current = fb.getDocument().getText(
                        0, fb.getDocument().getLength());

                String updated = current.substring(0, offset)
                        + text
                        + current.substring(offset + length);

                if (updated.matches("[0-9]*")
                        && updated.length() <= maxLength) {

                    super.replace(fb, offset, length, text, attr);
                }
            }
        });
    }

    public MedicalAppointmentSystem(UserSession currentUser) {
        this.currentUser = currentUser;
        setTitle("MediCare - Hospital Appointment System");
        setSize(1150, 720);
        setMinimumSize(new Dimension(950, 600));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // Apply digits-only restrictions.
        allowDigitsOnly(ageField, 3);
        allowDigitsOnly(phoneField, 10);
        nameField.setText(currentUser.name);
        phoneField.setText(currentUser.phone == null ? "" : currentUser.phone);
        if (!currentUser.isStaff()) {
            nameField.setEditable(false);
            phoneField.setEditable(false);
        }

        buildInterface();
        departmentBox.addActionListener(e -> updateDoctors());
        try {
            loadAppointments();
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not load appointments from medicare_db.\n" + ex.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
        updateDoctors();

        // Check missed appointments every 15 seconds.
        if (currentUser.isStaff()) {
            new Timer(15000, e -> checkMissedAppointments()).start();
        }

        setVisible(true);
    }

    private void buildInterface() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(245, 248, 252));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(true);
        header.setBackground(new Color(20, 60, 105));
        header.setPreferredSize(new Dimension(1000, 65));

        JButton backButton = new JButton("← Back");
        backButton.setEnabled(false);
        backButton.setFocusable(false);
        header.add(backButton, BorderLayout.WEST);

        JLabel heading = new JLabel("MediCare | "
                + (currentUser.isStaff() ? "Hospital Staff" : "Patient: " + currentUser.name));
        heading.setForeground(Color.WHITE);
        heading.setFont(new Font("SansSerif", Font.BOLD, 23));
        header.add(heading, BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        if (currentUser.isStaff()) {
            tabs.addTab("Hospital Dashboard", buildDashboardPanel());
            tabs.addTab("Bin / Missed Appointments", buildBinPanel());
        } else {
            tabs.addTab("Book Appointment", buildBookingPanel());
            tabs.addTab("My Appointments", buildDashboardPanel());
            tabs.addTab("My Cancelled / Missed", buildBinPanel());
        }

        Deque<Integer> tabHistory = new ArrayDeque<>();
        int[] previousTab = {tabs.getSelectedIndex()};
        boolean[] goingBack = {false};
        tabs.addChangeListener(e -> {
            int selected = tabs.getSelectedIndex();
            if (goingBack[0]) {
                goingBack[0] = false;
            } else if (selected != previousTab[0]) {
                tabHistory.push(previousTab[0]);
            }
            previousTab[0] = selected;
            backButton.setEnabled(!tabHistory.isEmpty());
        });
        backButton.addActionListener(e -> {
            if (!tabHistory.isEmpty()) {
                goingBack[0] = true;
                tabs.setSelectedIndex(tabHistory.pop());
                backButton.setEnabled(!tabHistory.isEmpty());
            }
        });

        root.add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        JLabel footerText = new JLabel(
                "  Patient Care | Attendance | Reappointment Fee: Rs. 100");
        footer.add(footerText, BorderLayout.WEST);
        JButton logoutButton = new JButton("Logout");
        logoutButton.addActionListener(e -> logout());
        JPanel logoutPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        logoutPanel.add(logoutButton);
        footer.add(logoutPanel, BorderLayout.EAST);
        footer.setBorder(BorderFactory.createEmptyBorder(10, 5, 10, 5));
        root.add(footer, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private void logout() {
        int answer = JOptionPane.showConfirmDialog(this,
                "Log out of MediCare?", "Confirm Logout", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;
        dispose();
        SwingUtilities.invokeLater(MedicalAppointmentSystem::startLoginFlow);
    }

    private JPanel buildBookingPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(new Color(245, 248, 252));
        panel.setBorder(BorderFactory.createEmptyBorder(15, 30, 15, 30));

        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(7, 8, 7, 8);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;

        JLabel title = new JLabel("Patient Appointment Booking");
        title.setFont(new Font("SansSerif", Font.BOLD, 21));

        g.gridx = 0;
        g.gridy = 0;
        g.gridwidth = 2;
        panel.add(title, g);
        g.gridwidth = 1;

        addField(panel, g, 1, 0, "Patient Name", nameField);
        addField(panel, g, 1, 1, "Age (digits only)", ageField);
        addField(panel, g, 2, 0, "Phone (10 digits only)", phoneField);
        addField(panel, g, 2, 1, "Gender", genderBox);
        addField(panel, g, 3, 0, "Department", departmentBox);
        addField(panel, g, 3, 1, "Doctor", doctorBox);
        addField(panel, g, 4, 0, "Date (YYYY-MM-DD)", dateField);
        addField(panel, g, 4, 1, "Appointment Time", timeBox);

        g.gridx = 0;
        g.gridy = 5;
        panel.add(new JLabel("Symptoms / Reason"), g);

        symptomsArea.setLineWrap(true);
        symptomsArea.setWrapStyleWord(true);

        g.gridx = 1;
        panel.add(new JScrollPane(symptomsArea), g);

        JButton bookButton = new JButton("Confirm Appointment");
        bookButton.setBackground(new Color(22, 115, 90));
        bookButton.setForeground(Color.WHITE);
        bookButton.setFont(new Font("SansSerif", Font.BOLD, 15));
        bookButton.addActionListener(e -> bookAppointment());

        g.gridx = 0;
        g.gridy = 6;
        g.gridwidth = 2;
        panel.add(bookButton, g);

        JLabel note = new JLabel(
                "Unattended appointments move to Bin after 30 minutes.");
        g.gridy = 7;
        panel.add(note, g);

        return panel;
    }

    private void addField(JPanel panel, GridBagConstraints g,
                          int row, int col, String label,
                          Component field) {
        JPanel group = new JPanel(new BorderLayout(4, 4));
        group.setOpaque(false);
        group.add(new JLabel(label), BorderLayout.NORTH);
        group.add(field, BorderLayout.CENTER);

        field.setPreferredSize(new Dimension(260, 30));

        g.gridx = col;
        g.gridy = row;
        panel.add(group, g);
    }

    private JPanel buildDashboardPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel(currentUser.isStaff()
                ? "Active Appointments - Mark Patient Attendance"
                : "Your Active Appointments");
        title.setFont(new Font("SansSerif", Font.BOLD, 19));
        panel.add(title, BorderLayout.NORTH);

        configureTable(activeTable);
        panel.add(new JScrollPane(activeTable), BorderLayout.CENTER);

        JButton cameButton = new JButton("Mark as Came");
        cameButton.addActionListener(e -> markCame());

        JButton cancelButton = new JButton(currentUser.isStaff()
                ? "Cancel Appointment" : "Cancel My Appointment");
        cancelButton.addActionListener(e -> cancelAppointment());

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> {
            if (currentUser.isStaff()) checkMissedAppointments();
            refreshFromDatabase();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(refreshButton);
        if (currentUser.isStaff()) buttons.add(cameButton);
        buttons.add(cancelButton);

        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildBinPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel(currentUser.isStaff()
                ? "Bin - Cancelled and Not Attended Appointments"
                : "Your Cancelled and Not Attended Appointments");
        title.setFont(new Font("SansSerif", Font.BOLD, 19));
        panel.add(title, BorderLayout.NORTH);

        configureTable(binTable);
        panel.add(new JScrollPane(binTable), BorderLayout.CENTER);

        JButton reappointButton = new JButton(
                "Reappoint Not-Attended Patient (+ Rs. 100)");
        reappointButton.addActionListener(e -> reappoint());

        JButton refreshButton = new JButton("Refresh Bin");
        refreshButton.addActionListener(e -> {
            if (currentUser.isStaff()) checkMissedAppointments();
            refreshFromDatabase();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(refreshButton);
        if (currentUser.isStaff()) buttons.add(reappointButton);

        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void configureTable(JTable table) {
        table.setRowHeight(27);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
    }

    private void updateDoctors() {
        String department = (String) departmentBox.getSelectedItem();
        doctorBox.removeAllItems();

        for (String entry : doctors) {
            String[] parts = entry.split("\\|");
            if (parts[1].equals(department)) {
                doctorBox.addItem(parts[0]);
            }
        }
    }

    private int getConsultationFee(String doctor) {
        for (String entry : doctors) {
            String[] parts = entry.split("\\|");
            if (parts[0].equals(doctor)) {
                return Integer.parseInt(parts[2]);
            }
        }
        return 0;
    }


    private static final String INSERT_SQL =
            "INSERT INTO appointments (appointment_id, patient_user_id, patient_name, age, phone, gender, "
            + "doctor, department, appointment_date, appointment_time, symptoms, "
            + "consultation_fee, reappointment_fee, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private void loadAppointments() throws SQLException {
        active.clear();
        bin.clear();
        bookedSlots.clear();
        String sql = "SELECT appointment_id, patient_user_id, patient_name, age, phone, gender, doctor, department, "
                + "appointment_date, appointment_time, symptoms, consultation_fee, reappointment_fee, status "
                + "FROM appointments "
                + (currentUser.isStaff() ? "" : "WHERE patient_user_id = ? ")
                + "ORDER BY appointment_id";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     sql)) {
            if (!currentUser.isStaff()) ps.setLong(1, currentUser.userId);
            try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("appointment_id");
                Object patientIdValue = rs.getObject("patient_user_id");
                Long patientUserId = patientIdValue == null ? null
                        : ((Number) patientIdValue).longValue();
                Appointment a = new Appointment(id, rs.getString("patient_name"),
                        rs.getString("phone"), rs.getString("gender"), rs.getInt("age"),
                        rs.getString("doctor"), rs.getString("department"),
                        rs.getDate("appointment_date").toLocalDate().toString(),
                        rs.getTime("appointment_time").toLocalTime().format(timeFormat),
                        rs.getString("symptoms"),
                        rs.getInt("consultation_fee"), rs.getInt("reappointment_fee"),
                        rs.getString("status"), patientUserId);
                if ("Booked".equals(a.status) || "Came".equals(a.status)) {
                    active.add(a);
                    if ("Booked".equals(a.status)) bookedSlots.add(a.slotKey());
                } else {
                    bin.add(a);
                }
            }
            }
        }
        refreshTables();
    }

    /** Allocate a globally unique, restart-safe ID from MySQL's AUTO_INCREMENT. */
    private String allocateAppointmentId(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO appointment_id_sequence () VALUES ()",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Could not allocate an appointment ID.");
                return "APT" + keys.getLong(1);
            }
        }
    }

    private void refreshFromDatabase() {
        try {
            loadAppointments();
        } catch (SQLException ex) {
            error("Could not refresh appointments: " + ex.getMessage());
        }
    }

    private void insertAppointment(Connection con, Appointment a) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(INSERT_SQL)) {
            ps.setString(1, a.id);
            if (a.patientUserId == null) ps.setNull(2, java.sql.Types.BIGINT);
            else ps.setLong(2, a.patientUserId);
            ps.setString(3, a.name);
            ps.setInt(4, a.age);
            ps.setString(5, a.phone);
            ps.setString(6, a.gender);
            ps.setString(7, a.doctor);
            ps.setString(8, a.department);
            ps.setDate(9, java.sql.Date.valueOf(a.date));
            ps.setTime(10, java.sql.Time.valueOf(LocalTime.parse(a.time, timeFormat)));
            ps.setString(11, a.symptoms);
            ps.setInt(12, a.consultationFee);
            ps.setInt(13, a.reappointmentFee);
            ps.setString(14, a.status);
            ps.executeUpdate();
        }
    }

    private boolean slotIsBooked(Connection con, Appointment a) throws SQLException {
        String sql = "SELECT 1 FROM appointments WHERE doctor = ? AND appointment_date = ? "
                + "AND appointment_time = ? AND status = 'Booked' LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, a.doctor);
            ps.setDate(2, java.sql.Date.valueOf(a.date));
            ps.setTime(3, java.sql.Time.valueOf(LocalTime.parse(a.time, timeFormat)));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void updateStatus(Appointment a, String status) throws SQLException {
        if (!currentUser.isStaff() && !"Cancelled".equals(status)) {
            throw new SQLException("Patients may only cancel their own appointments.");
        }
        String sql = "UPDATE appointments SET status = ? WHERE appointment_id = ? AND status = ?"
                + (currentUser.isStaff() ? "" : " AND patient_user_id = ?");
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setString(2, a.id);
                ps.setString(3, a.status);
                if (!currentUser.isStaff()) ps.setLong(4, currentUser.userId);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("The appointment changed in the database. Refresh and try again.");
                }
                con.commit();
                a.status = status;
            } catch (SQLException ex) {
                con.rollback();
                throw ex;
            }
        }
    }

    private void persistMissed(Appointment a) throws SQLException {
        updateStatus(a, "Not Attended");
    }

    private void bookAppointment() {
        if (currentUser.isStaff()) {
            error("Only patient accounts can book appointments.");
            return;
        }
        String name = nameField.getText().trim();
        String ageText = ageField.getText().trim();
        String phone = phoneField.getText().trim();
        String doctor = (String) doctorBox.getSelectedItem();
        String department = (String) departmentBox.getSelectedItem();
        String date = dateField.getText().trim();
        String time = (String) timeBox.getSelectedItem();
        String symptoms = symptomsArea.getText().trim();

        if (name.isEmpty() || ageText.isEmpty() || phone.isEmpty()
                || doctor == null || symptoms.isEmpty()) {
            error("Please fill in all required fields.");
            return;
        }

        int age;
        try {
            age = Integer.parseInt(ageText);
            if (age < 1 || age > 120) {
                error("Age must be between 1 and 120.");
                return;
            }
        } catch (NumberFormatException ex) {
            error("Age must be a number.");
            return;
        }

        if (!phone.matches("[0-9]{10}")) {
            error("Enter a valid 10-digit phone number.");
            return;
        }

        LocalDate appointmentDate;
        LocalTime appointmentTime;

        try {
            appointmentDate = LocalDate.parse(date);
            appointmentTime = LocalTime.parse(time, timeFormat);
        } catch (DateTimeParseException ex) {
            error("Enter a valid date and time.");
            return;
        }

        if (LocalDateTime.of(appointmentDate, appointmentTime)
                .isBefore(LocalDateTime.now())) {
            error("Choose a future appointment time.");
            return;
        }

        Appointment appointment = new Appointment(
                null, name, phone,
                genderBox.getSelectedItem().toString(), age,
                doctor, department, date, time, symptoms,
                getConsultationFee(doctor), 0, "Booked", currentUser.userId);

        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                if (slotIsBooked(con, appointment)) {
                    con.rollback();
                    error("This doctor's time slot is already booked.");
                    return;
                }
                appointment.id = allocateAppointmentId(con);
                insertAppointment(con, appointment);
                con.commit();
                refreshFromDatabase();
            } catch (SQLException ex) {
                con.rollback();
                if (ex.getErrorCode() == 1062) {
                    error("This doctor's time slot or appointment ID is already in use. Refresh and try again.");
                } else {
                    error("Could not save appointment: " + ex.getMessage());
                }
                return;
            }
        } catch (SQLException ex) {
            error("Could not connect to medicare_db: " + ex.getMessage());
            return;
        }

        JOptionPane.showMessageDialog(this,
                "APPOINTMENT CONFIRMED\n\n"
                + "ID: " + appointment.id + "\n"
                + "Patient: " + name + "\n"
                + "Doctor: " + doctor + "\n"
                + "Date: " + date + "\n"
                + "Time: " + time + "\n"
                + "Consultation Fee: Rs. " + appointment.totalFee());

        nameField.setText("");
        ageField.setText("");
        phoneField.setText("");
        symptomsArea.setText("");
    }

    private Appointment selectedAppointment(
            JTable table, ArrayList<Appointment> source) {
        int viewRow = table.getSelectedRow();

        if (viewRow == -1) {
            error("Please select an appointment first.");
            return null;
        }

        int row = table.convertRowIndexToModel(viewRow);
        String id = table.getModel().getValueAt(row, 0).toString();

        for (Appointment a : source) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }

    private void markCame() {
        if (!currentUser.isStaff()) {
            error("Only hospital staff can mark an appointment as Came.");
            return;
        }
        Appointment a = selectedAppointment(activeTable, active);
        if (a == null) return;

        if (!a.status.equals("Booked")) {
            error("Only booked appointments can be marked Came.");
            return;
        }

        try {
            updateStatus(a, "Came");
            refreshFromDatabase();
        } catch (SQLException ex) {
            error("Could not update appointment: " + ex.getMessage());
            return;
        }

        JOptionPane.showMessageDialog(this,
                a.name + " has been marked as CAME.");
    }

    private void cancelAppointment() {
        Appointment a = selectedAppointment(activeTable, active);
        if (a == null) return;

        if (!a.status.equals("Booked")) {
            error("Only booked appointments can be cancelled.");
            return;
        }

        int answer = JOptionPane.showConfirmDialog(this,
                "Move this appointment to the Bin?",
                "Confirm Cancellation",
                JOptionPane.YES_NO_OPTION);

        if (answer == JOptionPane.YES_OPTION) {
            try {
                updateStatus(a, "Cancelled");
                refreshFromDatabase();
            } catch (SQLException ex) {
                error("Could not cancel appointment: " + ex.getMessage());
            }
        }
    }

    private void checkMissedAppointments() {
        if (!currentUser.isStaff()) return;
        LocalDateTime now = LocalDateTime.now();
        ArrayList<Appointment> missed = new ArrayList<>();

        for (Appointment a : active) {
            if (!a.status.equals("Booked")) continue;

            try {
                LocalDate date = LocalDate.parse(a.date);
                LocalTime time = LocalTime.parse(a.time, timeFormat);

                LocalDateTime slotEnd =
                        LocalDateTime.of(date, time).plusMinutes(30);

                if (!now.isBefore(slotEnd)) {
                    missed.add(a);
                }
            } catch (DateTimeParseException ignored) {
                // Ignore invalid appointment dates.
            }
        }

        if (!missed.isEmpty()) {
            try (Connection con = DBConnection.getConnection()) {
                con.setAutoCommit(false);
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE appointments SET status = 'Not Attended' "
                        + "WHERE appointment_id = ? AND status = 'Booked'")) {
                    for (Appointment a : missed) {
                        ps.setString(1, a.id);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    con.commit();
                } catch (SQLException ex) {
                    con.rollback();
                    error("Could not move missed appointments to Bin: " + ex.getMessage());
                    return;
                }
                refreshFromDatabase();
            } catch (SQLException ex) {
                error("Could not connect to medicare_db: " + ex.getMessage());
            }
        }
    }

    private void reappoint() {
        if (!currentUser.isStaff()) {
            error("Only hospital staff can reappoint a patient.");
            return;
        }
        Appointment old = selectedAppointment(binTable, bin);
        if (old == null) return;

        if (!old.status.equals("Not Attended")) {
            error("Only Not Attended appointments can be reappointed.");
            return;
        }

        JTextField newDate = new JTextField(
                LocalDate.now().plusDays(1).toString());

        JComboBox<String> newTime = new JComboBox<>(
                new String[]{"09:00 AM", "10:00 AM", "11:00 AM",
                        "12:00 PM", "02:00 PM", "03:00 PM", "04:00 PM"});

        JPanel form = new JPanel(new GridLayout(0, 1, 5, 5));
        form.add(new JLabel("New appointment date (YYYY-MM-DD):"));
        form.add(newDate);
        form.add(new JLabel("New appointment time:"));
        form.add(newTime);
        form.add(new JLabel("Additional reappointment fee: Rs. 100"));

        int result = JOptionPane.showConfirmDialog(this,
                form, "Reappoint " + old.name,
                JOptionPane.OK_CANCEL_OPTION);

        if (result != JOptionPane.OK_OPTION) return;

        String date = newDate.getText().trim();
        String time = newTime.getSelectedItem().toString();

        try {
            LocalDate d = LocalDate.parse(date);
            LocalTime t = LocalTime.parse(time, timeFormat);

            if (LocalDateTime.of(d, t).isBefore(LocalDateTime.now())) {
                error("Choose a future date and time.");
                return;
            }
        } catch (DateTimeParseException ex) {
            error("Invalid date. Use YYYY-MM-DD.");
            return;
        }

        Appointment replacement = new Appointment(
                null, old.name, old.phone, old.gender,
                old.age, old.doctor, old.department, date, time,
                old.symptoms, old.consultationFee, 100, "Booked", old.patientUserId);

        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                if (slotIsBooked(con, replacement)) {
                    con.rollback();
                    error("That doctor's time slot is already booked.");
                    return;
                }
                replacement.id = allocateAppointmentId(con);
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE appointments SET status = 'Reappointed' "
                        + "WHERE appointment_id = ? AND status = 'Not Attended'")) {
                    ps.setString(1, old.id);
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("This appointment is no longer eligible for reappointment.");
                    }
                }
                insertAppointment(con, replacement);
                con.commit();
                refreshFromDatabase();
            } catch (SQLException ex) {
                con.rollback();
                error("Could not save reappointment: " + ex.getMessage());
                return;
            }
        } catch (SQLException ex) {
            error("Could not connect to medicare_db: " + ex.getMessage());
            return;
        }

        JOptionPane.showMessageDialog(this,
                "REAPPOINTMENT CONFIRMED\n\n"
                + "New Appointment ID: " + replacement.id + "\n"
                + "Patient: " + replacement.name + "\n"
                + "Doctor: " + replacement.doctor + "\n"
                + "New Date: " + replacement.date + "\n"
                + "New Time: " + replacement.time + "\n\n"
                + "Consultation Fee: Rs. "
                + replacement.consultationFee + "\n"
                + "Reappointment Fee: Rs. 100\n"
                + "Total Fee: Rs. " + replacement.totalFee());
    }

    private void refreshTables() {
        activeModel.setRowCount(0);
        binModel.setRowCount(0);

        for (Appointment a : active) {
            activeModel.addRow(new Object[]{
                    a.id, a.name, a.doctor, a.department,
                    a.date, a.time, "Rs. " + a.totalFee(), a.status
            });
        }

        for (Appointment a : bin) {
            binModel.addRow(new Object[]{
                    a.id, a.name, a.doctor, a.department,
                    a.date, a.time, "Rs. " + a.totalFee(), a.status
            });
        }
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message,
                "Hospital System", JOptionPane.WARNING_MESSAGE);
    }

    private static byte[] hashPassword(char[] password, byte[] salt) throws SQLException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PASSWORD_ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new SQLException("Password hashing is unavailable.", ex);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static byte[] newSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private static void ensureHospitalAccount() throws SQLException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement check = con.prepareStatement(
                     "SELECT user_id FROM app_users WHERE login_name = ? AND role = 'STAFF'")) {
            check.setString(1, "hospital");
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) return;
            }
        }
        byte[] salt = newSalt();
        byte[] hash = hashPassword("admin@123".toCharArray(), salt);
        try (Connection con = DBConnection.getConnection();
             PreparedStatement insert = con.prepareStatement(
                     "INSERT INTO app_users (login_name, full_name, phone, password_salt, password_hash, role) "
                     + "VALUES (?, ?, NULL, ?, ?, 'STAFF')")) {
            insert.setString(1, "hospital");
            insert.setString(2, "Hospital Staff");
            insert.setBytes(3, salt);
            insert.setBytes(4, hash);
            insert.executeUpdate();
        }
    }

    private static UserSession authenticate(String loginField, String role,
                                            char[] password) throws SQLException {
        String column = "STAFF".equals(role) ? "login_name" : "phone";
        String sql = "SELECT user_id, full_name, phone, password_salt, password_hash "
                + "FROM app_users WHERE " + column + " = ? AND role = ?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, loginField.trim());
            ps.setString(2, role);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    java.util.Arrays.fill(password, '\0');
                    return null;
                }
                byte[] expected = rs.getBytes("password_hash");
                byte[] actual = hashPassword(password, rs.getBytes("password_salt"));
                if (!MessageDigest.isEqual(expected, actual)) return null;
                long id = rs.getLong("user_id");
                return new UserSession(id, rs.getString("full_name"),
                        rs.getString("phone"), role);
            }
        } catch (SQLException ex) {
            java.util.Arrays.fill(password, '\0');
            throw ex;
        }
    }

    private static void registerPatient(String name, String phone,
                                        char[] password) throws SQLException {
        byte[] salt = newSalt();
        byte[] hash = hashPassword(password, salt);
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO app_users (login_name, full_name, phone, password_salt, password_hash, role) "
                     + "VALUES (NULL, ?, ?, ?, ?, 'PATIENT')",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name.trim());
                ps.setString(2, phone.trim());
                ps.setBytes(3, salt);
                ps.setBytes(4, hash);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Could not retrieve the new patient account ID.");
                    long userId = keys.getLong(1);
                    try (PreparedStatement link = con.prepareStatement(
                            "UPDATE appointments SET patient_user_id = ? "
                            + "WHERE patient_user_id IS NULL AND phone = ? "
                            + "AND LOWER(TRIM(patient_name)) = LOWER(TRIM(?))")) {
                        link.setLong(1, userId);
                        link.setString(2, phone.trim());
                        link.setString(3, name.trim());
                        link.executeUpdate();
                    }
                }
                con.commit();
            } catch (SQLException ex) {
                con.rollback();
                throw ex;
            }
        }
    }

    private static class LoginDialog extends JDialog {
        private UserSession authenticatedUser;

        private LoginDialog() {
            super((Frame) null, "MediCare Login", true);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Patient Login", patientLoginPanel());
            tabs.addTab("Register", registrationPanel(tabs));
            tabs.addTab("Hospital Staff", staffLoginPanel());
            setContentPane(tabs);
            setSize(440, 310);
            setLocationRelativeTo(null);
        }

        static UserSession showLogin() throws SQLException {
            ensureHospitalAccount();
            LoginDialog dialog = new LoginDialog();
            dialog.setVisible(true);
            return dialog.authenticatedUser;
        }

        private JPanel patientLoginPanel() {
            JPanel panel = new JPanel(new GridLayout(0, 1, 8, 8));
            panel.setBorder(BorderFactory.createEmptyBorder(18, 28, 18, 28));
            JTextField phone = new JTextField();
            JPasswordField password = new JPasswordField();
            panel.add(new JLabel("Mobile number"));
            panel.add(phone);
            panel.add(new JLabel("Password"));
            panel.add(password);
            JButton login = new JButton("Login");
            login.addActionListener(e -> {
                try {
                    authenticatedUser = authenticate(phone.getText(), "PATIENT", password.getPassword());
                    if (authenticatedUser == null) {
                        JOptionPane.showMessageDialog(this, "Mobile number or password is incorrect.");
                    } else dispose();
                } catch (SQLException ex) {
                    JOptionPane.showMessageDialog(this, "Login failed: " + ex.getMessage());
                }
                password.setText("");
            });
            panel.add(login);
            return panel;
        }

        private JPanel staffLoginPanel() {
            JPanel panel = new JPanel(new GridLayout(0, 1, 8, 8));
            panel.setBorder(BorderFactory.createEmptyBorder(18, 28, 18, 28));
            JTextField loginName = new JTextField("hospital");
            JPasswordField password = new JPasswordField();
            panel.add(new JLabel("Staff login name"));
            panel.add(loginName);
            panel.add(new JLabel("Password"));
            panel.add(password);
            JButton login = new JButton("Staff Login");
            login.addActionListener(e -> {
                try {
                    authenticatedUser = authenticate(loginName.getText(), "STAFF", password.getPassword());
                    if (authenticatedUser == null) {
                        JOptionPane.showMessageDialog(this, "Staff login name or password is incorrect.");
                    } else dispose();
                } catch (SQLException ex) {
                    JOptionPane.showMessageDialog(this, "Login failed: " + ex.getMessage());
                }
                password.setText("");
            });
            panel.add(login);
            return panel;
        }

        private JPanel registrationPanel(JTabbedPane tabs) {
            JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
            panel.setBorder(BorderFactory.createEmptyBorder(12, 28, 12, 28));
            JTextField name = new JTextField();
            JTextField phone = new JTextField();
            JPasswordField password = new JPasswordField();
            JPasswordField confirm = new JPasswordField();
            panel.add(new JLabel("Full name"));
            panel.add(name);
            panel.add(new JLabel("10-digit mobile number (used to log in)"));
            panel.add(phone);
            panel.add(new JLabel("Password (at least 8 characters)"));
            panel.add(password);
            panel.add(new JLabel("Confirm password"));
            panel.add(confirm);
            JButton register = new JButton("Create Patient Account");
            register.addActionListener(e -> {
                char[] pass = password.getPassword();
                char[] confirmPass = confirm.getPassword();
                if (name.getText().trim().isEmpty() || !phone.getText().matches("[0-9]{10}")) {
                    JOptionPane.showMessageDialog(this, "Enter your name and a valid 10-digit mobile number.");
                } else if (pass.length < 8) {
                    JOptionPane.showMessageDialog(this, "Choose a password with at least 8 characters.");
                } else if (!java.util.Arrays.equals(pass, confirmPass)) {
                    JOptionPane.showMessageDialog(this, "The passwords do not match.");
                } else {
                    try {
                        registerPatient(name.getText(), phone.getText(), pass);
                        JOptionPane.showMessageDialog(this, "Account created. Log in with your mobile number and password.");
                        tabs.setSelectedIndex(0);
                        name.setText("");
                        phone.setText("");
                    } catch (SQLException ex) {
                        java.util.Arrays.fill(pass, '\0');
                        JOptionPane.showMessageDialog(this,
                                ex.getErrorCode() == 1062 ? "That mobile number is already registered."
                                        : "Registration failed: " + ex.getMessage());
                    }
                }
                java.util.Arrays.fill(pass, '\0');
                java.util.Arrays.fill(confirmPass, '\0');
                password.setText("");
                confirm.setText("");
            });
            panel.add(register);
            return panel;
        }
    }

    private static void startLoginFlow() {
        try {
            UserSession user = LoginDialog.showLogin();
            if (user != null) new MedicalAppointmentSystem(user);
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(null,
                    "Could not prepare login/database: " + ex.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MedicalAppointmentSystem::startLoginFlow);
    }
}

