export type SeatStatus = 'AVAILABLE' | 'HELD' | 'SOLD';

export interface EventData {
  id: string;
  title: string;
  venueName: string;
  totalSeats: number;
  startsAt: string;
  saleStartsAt: string;
  createdAt: string;
}

export interface Seat {
  id: string;
  section: string;
  row: string;
  number: string;
  price: number;
  status: SeatStatus;
  holdExpiresAt: string | null;
}

export interface SeatMapResponse {
  eventId: string;
  seats: Seat[];
}

export interface HoldResponse {
  holdToken: string;
  seatIds: string[];
  expiresAt: string;
}

export interface OrderResponse {
  orderId: string;
  eventId: string;
  userId: string;
  totalAmount: number;
  status: string;
  paymentReference: string;
  ticketQrSignature: string;
  seatIds: string[];
  createdAt: string;
}

export interface SeatUpdateMessage {
  eventId: string;
  seatIds: string[];
  status: SeatStatus;
  holdExpiresAt: string | null;
}
