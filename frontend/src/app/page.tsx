'use client';

import React, { useEffect, useState, useCallback, useMemo } from 'react';
import {
  Container,
  Box,
  Typography,
  Button,
  AppBar,
  Toolbar,
  CircularProgress,
  Alert,
  Snackbar,
  Paper,
  Slide,
} from '@mui/material';
import FlashOnIcon from '@mui/icons-material/FlashOn';
import ShoppingCartCheckoutIcon from '@mui/icons-material/ShoppingCartCheckout';
import LockClockIcon from '@mui/icons-material/LockClock';
import VenueSeatMap from '../components/VenueSeatMap';
import HoldTimer from '../components/HoldTimer';
import CheckoutDialog from '../components/CheckoutDialog';
import OrderSuccessDialog from '../components/OrderSuccessDialog';
import { EventData, Seat, HoldResponse, OrderResponse, SeatUpdateMessage } from '../types';

export default function FlashSalePage() {
  const [event, setEvent] = useState<EventData | null>(null);
  const [seats, setSeats] = useState<Seat[]>([]);
  const [selectedSeatIds, setSelectedSeatIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [holdData, setHoldData] = useState<HoldResponse | null>(null);
  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [completedOrder, setCompletedOrder] = useState<OrderResponse | null>(null);
  const [notification, setNotification] = useState<string | null>(null);
  const [userId, setUserId] = useState<string>('');

  // 1. Initialize persistent client userId
  useEffect(() => {
    let storedId = localStorage.getItem('flash_sale_user_id');
    if (!storedId) {
      storedId = crypto.randomUUID();
      localStorage.setItem('flash_sale_user_id', storedId);
    }
    setUserId(storedId);
  }, []);

  // 2. Hydrate Event and Seat Map
  const loadEventAndSeats = useCallback(async () => {
    try {
      setLoading(true);
      const eventsRes = await fetch('/api/v1/events');
      const events: EventData[] = await eventsRes.json();
      if (!events || events.length === 0) return;

      const activeEvent = events[0];
      setEvent(activeEvent);

      const seatsRes = await fetch(`/api/v1/events/${activeEvent.id}/seats`);
      const seatsData = await seatsRes.json();
      setSeats(seatsData.seats || []);
    } catch (err: any) {
      console.error('Failed to hydrate seat map:', err);
      setNotification('Failed to connect to backend engine. Ensure Spring Boot is running.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadEventAndSeats();
  }, [loadEventAndSeats]);

  // 3. Connect to Server-Sent Events (SSE) Stream for Live Delta Broadcasts
  useEffect(() => {
    if (!event) return;

    const streamUrl = `/api/v1/events/${event.id}/stream`;
    const eventSource = new EventSource(streamUrl);

    eventSource.addEventListener('seat-update', (e: MessageEvent) => {
      try {
        const update: SeatUpdateMessage = JSON.parse(e.data);
        setSeats((prevSeats) =>
          prevSeats.map((seat) => {
            if (update.seatIds.includes(seat.id)) {
              return {
                ...seat,
                status: update.status,
                holdExpiresAt: update.holdExpiresAt,
              };
            }
            return seat;
          })
        );

        // If another user grabbed any currently selected seat, deselect it
        if (update.status !== 'AVAILABLE') {
          setSelectedSeatIds((prevSelected) => {
            const conflict = prevSelected.some((id) => update.seatIds.includes(id));
            if (conflict && !holdData) {
              setNotification('One of your selected seats was just reserved by another buyer.');
              return prevSelected.filter((id) => !update.seatIds.includes(id));
            }
            return prevSelected;
          });
        }
      } catch (err) {
        console.error('SSE JSON parse error:', err);
      }
    });

    eventSource.onerror = () => {
      console.warn('SSE stream reconnecting...');
    };

    return () => {
      eventSource.close();
    };
  }, [event, holdData]);

  // Seat toggle handler (Max 4 seats per PRD rule FR-2.1)
  const handleToggleSeat = (seatId: string) => {
    if (holdData) return; // Cannot alter selection while hold is active

    setSelectedSeatIds((prev) => {
      if (prev.includes(seatId)) {
        return prev.filter((id) => id !== seatId);
      }
      if (prev.length >= 4) {
        setNotification('Limit reached: You can hold at most 4 seats per transaction.');
        return prev;
      }
      return [...prev, seatId];
    });
  };

  // Hold Seats mutation: calls POST /api/v1/events/{id}/seats/hold
  const handleHoldSeats = async () => {
    if (!event || selectedSeatIds.length === 0) return;

    try {
      const res = await fetch(`/api/v1/events/${event.id}/seats/hold`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-User-Id': userId,
        },
        body: JSON.stringify({ seatIds: selectedSeatIds }),
      });

      const data = await res.json();

      if (!res.ok) {
        throw new Error(data.detail || data.title || 'Seat hold conflict');
      }

      setHoldData(data);
      setNotification(`Locked ${selectedSeatIds.length} seat(s) for 10 minutes.`);
    } catch (err: any) {
      setNotification(err.message || 'Seat unavailable conflict');
    }
  };

  const handleHoldExpired = () => {
    setHoldData(null);
    setSelectedSeatIds([]);
    setCheckoutOpen(false);
    setNotification('Your 10-minute hold reservation has expired and seats were released.');
    loadEventAndSeats();
  };

  const heldSeats = useMemo(() => {
    return seats.filter((s) => selectedSeatIds.includes(s.id));
  }, [seats, selectedSeatIds]);

  const selectedTotal = useMemo(() => {
    return heldSeats.reduce((sum, s) => sum + Number(s.price), 0);
  }, [heldSeats]);

  return (
    <Box sx={{ minHeight: '100vh', pb: 14, bgcolor: 'background.default' }}>
      {/* Top Navbar */}
      <AppBar position="sticky" elevation={0} sx={{ bgcolor: 'rgba(17,24,39,0.85)', backdropFilter: 'blur(12px)', borderBottom: '1px solid rgba(255,255,255,0.08)' }}>
        <Toolbar sx={{ justifyContent: 'space-between' }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
            <FlashOnIcon sx={{ color: 'secondary.main', fontSize: 28 }} />
            <Typography variant="h6" fontWeight={700} sx={{ background: 'linear-gradient(45deg, #f9fafb, #a5b4fc)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent' }}>
              FlashSale Engine
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
            {holdData && (
              <HoldTimer expiresAt={holdData.expiresAt} onExpired={handleHoldExpired} />
            )}
            <Typography variant="caption" sx={{ color: 'text.secondary', display: { xs: 'none', sm: 'block' } }}>
              Live Virtual Threads &bull; SSE Sync
            </Typography>
          </Box>
        </Toolbar>
      </AppBar>

      {/* Main Content */}
      <Container maxWidth="lg" sx={{ mt: 4 }}>
        {loading ? (
          <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '60vh', gap: 2 }}>
            <CircularProgress color="secondary" size={48} />
            <Typography variant="body1" color="text.secondary">
              Hydrating Venue Seat Map from Redis Cache...
            </Typography>
          </Box>
        ) : (
          <>
            {/* Event Header Banner */}
            {event && (
              <Box sx={{ textAlign: 'center', mb: 3 }}>
                <Typography variant="h4" fontWeight={800} gutterBottom>
                  {event.title}
                </Typography>
                <Typography variant="subtitle1" color="text.secondary">
                  {event.venueName} &bull; {new Date(event.startsAt).toLocaleDateString(undefined, { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })}
                </Typography>
              </Box>
            )}

            {/* Interactive Seat Map */}
            <VenueSeatMap
              seats={seats}
              selectedSeatIds={selectedSeatIds}
              onToggleSeat={handleToggleSeat}
              maxSelectionReached={selectedSeatIds.length >= 4}
            />
          </>
        )}
      </Container>

      {/* Sticky Bottom Action Bar */}
      <Slide direction="up" in={selectedSeatIds.length > 0} mountOnEnter unmountOnExit>
        <Paper
          elevation={6}
          sx={{
            position: 'fixed',
            bottom: 20,
            left: '50%',
            transform: 'translateX(-50%) !important',
            width: '90%',
            maxWidth: 640,
            p: 2,
            bgcolor: 'rgba(17, 24, 39, 0.95)',
            backdropFilter: 'blur(16px)',
            border: '1px solid rgba(255,255,255,0.12)',
            borderRadius: 4,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            boxShadow: '0 10px 30px rgba(0,0,0,0.5)',
            zIndex: 1000,
          }}
        >
          <Box>
            <Typography variant="body2" color="text.secondary">
              {selectedSeatIds.length} seat(s) selected
            </Typography>
            <Typography variant="h6" fontWeight={700} color="secondary.main">
              ${selectedTotal.toFixed(2)}
            </Typography>
          </Box>

          <Box sx={{ display: 'flex', gap: 1.5, alignItems: 'center' }}>
            {!holdData ? (
              <Button
                variant="contained"
                color="primary"
                onClick={handleHoldSeats}
                startIcon={<LockClockIcon />}
                size="large"
              >
                Hold Seats (10m)
              </Button>
            ) : (
              <Button
                variant="contained"
                color="secondary"
                onClick={() => setCheckoutOpen(true)}
                startIcon={<ShoppingCartCheckoutIcon />}
                size="large"
              >
                Checkout Now
              </Button>
            )}
          </Box>
        </Paper>
      </Slide>

      {/* Checkout Modal */}
      {holdData && (
        <CheckoutDialog
          open={checkoutOpen}
          onClose={() => setCheckoutOpen(false)}
          eventId={event?.id || ''}
          holdToken={holdData.holdToken}
          heldSeats={heldSeats}
          userId={userId}
          onOrderSuccess={(order) => {
            setCompletedOrder(order);
            setHoldData(null);
            setSelectedSeatIds([]);
            loadEventAndSeats();
          }}
        />
      )}

      {/* Order Confirmed Dialog */}
      <OrderSuccessDialog
        order={completedOrder}
        onClose={() => setCompletedOrder(null)}
      />

      {/* Notification Toast */}
      <Snackbar
        open={Boolean(notification)}
        autoHideDuration={5000}
        onClose={() => setNotification(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert onClose={() => setNotification(null)} severity="info" variant="filled" sx={{ width: '100%' }}>
          {notification}
        </Alert>
      </Snackbar>
    </Box>
  );
}
