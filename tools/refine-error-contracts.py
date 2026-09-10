from pathlib import Path
import re

# Explicit source-level business errors, never runtime exception-message guessing.
groups = {
 ('UNAUTHORIZED','INVALID_CREDENTIALS'): ['Invalid credentials.','No authorization token','Admin not found'],
 ('UNAUTHORIZED','AUTHENTICATION_REQUIRED'): ['Please log in before creating a support ticket.','Please log in before viewing support tickets.','Device authentication required.'],
 ('TOO_MANY_REQUESTS','ACCOUNT_LOCKED'): ['Account is locked. Try again later.'],
 ('FORBIDDEN','ACCOUNT_INACTIVE'): ['Account is disabled.','Assigned staff account is inactive.','This RFID card is currently inactive or frozen. Please contact Premier Transport support.'],
 ('FORBIDDEN','FORBIDDEN'): ['Super Admin approval is required for adjustments of 5000.00 or more.', 'Only Super Admin can create admins.', 'Only Super Admin can edit admins.', 'Only Super Admin can delete admins.', 'Only Super Admin can reset passwords.', 'Only Super Admin can reset Google Authenticator.'],
 ('NOT_FOUND','NOT_FOUND'): ['Transaction not found.', 'Passenger not found.', 'Driver not found.', 'Vehicle not found.', 'Admin not found.', 'Device not found.', 'Staff account not found.', 'Staff cash card not found.', 'Support ticket not found.'],
 ('CONFLICT','CONFLICT'): ['Transaction is not pending.', 'RFID UID already registered.', 'License number already registered.', 'Plate number already registered.', 'Username already exists.', 'Device ID already exists.', 'Cannot delete your own account.', 'Staff accounts do not use Google Authenticator.', 'No staff cash transactions exist for this date.', 'Use the resolve or reject action to close a ticket.', 'This support ticket is already closed and cannot be changed.'],
 ('CONFLICT','UID_RESERVED'): ['This RFID UID is already registered to a passenger.', 'This RFID UID is already registered as another staff cash card.', 'New RFID UID is already assigned to another card.'],
 ('CONFLICT','RECONCILIATION_REQUIRED'): ['Vehicle has no matching driver shift for this fare.'],
 ('BAD_REQUEST','INVALID_REQUEST'): ['Amount must be between 1.00 and 10000.00.', 'Adjustment reason is required.', 'RFID UID is required.', 'RFID UID is invalid.', 'Invalid card category.', 'Vehicle capacity must be at least 1.', 'Selected account is not staff.', 'Selected account is not a staff account.', 'Invalid RFID UID.', 'Payment request is required.', 'Request ID is invalid.', 'Email address is required so we can send your support update.', 'Admin notes are required when rejecting a ticket.', 'Card Number is required.', 'New RFID UID is required.'],
}
mapping={message:key for key,messages in groups.items() for message in messages}
for p in Path('src/main/java').rglob('*.java'):
    original=p.read_text(encoding='utf-8'); s=original
    for message,(status,code) in mapping.items():
        s=re.sub(r'new RuntimeException\(\s*"'+re.escape(message)+r'"\s*\)',
           f'new com.premier.exception.ClientException(org.springframework.http.HttpStatus.{status}, "{code}", "{message}")',s)
    if p.name=='StaffCashFareService.java':
        s=s.replace('new RuntimeException("Staff cash card is " + card.getStatus().name().toLowerCase() + ".")',
            'new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "CARD_INACTIVE", "Staff cash card is inactive.")')
    if p.name=='AdminService.java':
        s=s.replace('new RuntimeException(label + " is required.")',
            'new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", label + " is required.")')
    if p.name=='AdminController.java':
        s=re.sub(r'    @ExceptionHandler\([^\n]+\)\n    public ResponseEntity<\?> \w+\([^\n]+\) \{.*?\n    \}\n', '',s,flags=re.S)
    if s!=original:
        p.write_text(s,encoding='utf-8');print(p.name)
