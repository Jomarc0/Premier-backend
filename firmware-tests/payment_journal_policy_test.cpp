#include "PaymentJournalPolicy.h"
#include <cassert>
#include <iostream>
using namespace premier_payment;
int main() {
  assert(crc32("123456789", 9) == 0xcbf43926u);
  assert(crc32("complete", 8) != crc32("completE", 8));
  assert(classify(200, true, true, true, "") == Outcome::Succeeded);
  assert(classify(200, false, true, true, "") == Outcome::Pending);
  assert(classify(200, true, true, false, "") == Outcome::Pending);
  assert(classify(202, true, false, false, "PAYMENT_UNKNOWN") == Outcome::Pending);
  assert(classify(-1, false, false, false, "") == Outcome::Pending);
  assert(classify(503, true, false, false, "SERVICE_UNAVAILABLE") == Outcome::Pending);
  assert(classify(429, true, false, false, "RATE_LIMITED") == Outcome::Pending);
  assert(classify(422, true, false, false, "INSUFFICIENT_BALANCE") == Outcome::Rejected);
  assert(classify(410, true, false, false, "QR_EXPIRED") == Outcome::Rejected);
  assert(classify(409, true, false, false, "QR_ALREADY_USED") == Outcome::Review);
  assert(classify(409, true, false, false, "CONFLICT") == Outcome::Review);
  assert(classify(403, true, false, false, "DEVICE_REJECTED") == Outcome::Review);
  assert(classify(400, false, false, false, "") == Outcome::Review);
  assert(retryDelay(1) == 20 && retryDelay(100) <= 900);
  std::cout << "16 payment journal policy checks passed\n";
}
