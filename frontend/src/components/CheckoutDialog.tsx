'use client';

import React, { useState } from 'react';
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  Typography,
  Box,
  Divider,
  FormControl,
  InputLabel,
  Select,
  MenuItem,
  CircularProgress,
  Alert,
} from '@mui/material';
import LockIcon from '@mui/icons-material/Lock';
import CreditCardIcon from '@mui/icons-material/CreditCard';
import { Seat, OrderResponse } from '../types';

interface CheckoutDialogProps {
  open: boolean;
  onClose: () => void;
  eventId: string;
  holdToken: string;
  heldSeats: Seat[];
  userId: string;
  onOrderSuccess: (order: OrderResponse) => void;
}

export default function CheckoutDialog({
  open,
  onClose,
  eventId,
  holdToken,
  heldSeats,
  userId,
  onOrderSuccess,
}: CheckoutDialogProps) {
  const [paymentToken, setPaymentToken] = useState('pm_card_visa');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const total = heldSeats.reduce((sum, s) => sum + Number(s.price), 0);

  const handleCheckout = async () => {
    setLoading(true);
    setError(null);

    // Senior Best Practice: Generate client-side UUID v4 Idempotency Key
    const idempotencyKey = crypto.randomUUID();

    try {
      const res = await fetch('/api/v1/orders/checkout', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': idempotencyKey,
          'X-User-Id': userId,
        },
        body: JSON.stringify({
          eventId,
          holdToken,
          seatIds: heldSeats.map((s) => s.id),
          paymentMethodToken: paymentToken,
        }),
      });

      const data = await res.json();

      if (!res.ok) {
        throw new Error(data.detail || data.title || 'Checkout failed');
      }

      onOrderSuccess(data);
      onClose();
    } catch (err: any) {
      setError(err.message || 'An error occurred during payment capture');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Dialog open={open} onClose={loading ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <LockIcon color="primary" /> Complete Secure Purchase
      </DialogTitle>
      <DialogContent dividers>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        <Typography variant="subtitle2" color="text.secondary" gutterBottom>
          Order Summary ({heldSeats.length} seats)
        </Typography>

        <Box sx={{ my: 1 }}>
          {heldSeats.map((seat) => (
            <Box
              key={seat.id}
              sx={{
                display: 'flex',
                justifyContent: 'space-between',
                py: 0.75,
                borderBottom: '1px solid rgba(255,255,255,0.06)',
              }}
            >
              <Typography variant="body2">
                Section <strong>{seat.section}</strong> &bull; Row {seat.row}, Seat {seat.number}
              </Typography>
              <Typography variant="body2" fontWeight={600}>
                ${Number(seat.price).toFixed(2)}
              </Typography>
            </Box>
          ))}
        </Box>

        <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 2, pt: 1 }}>
          <Typography variant="h6">Total</Typography>
          <Typography variant="h6" color="secondary.main" fontWeight={700}>
            ${total.toFixed(2)}
          </Typography>
        </Box>

        <Divider sx={{ my: 2 }} />

        <FormControl fullWidth sx={{ mt: 1 }}>
          <InputLabel id="payment-select-label">Payment Simulation</InputLabel>
          <Select
            labelId="payment-select-label"
            value={paymentToken}
            label="Payment Simulation"
            onChange={(e) => setPaymentToken(e.target.value)}
          >
            <MenuItem value="pm_card_visa">
              Visa ending in 4242 (Simulate Success)
            </MenuItem>
            <MenuItem value="pm_fail">
              Declined Card (Simulate Circuit Breaker Fallback)
            </MenuItem>
          </Select>
        </FormControl>
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} disabled={loading} color="inherit">
          Cancel
        </Button>
        <Button
          onClick={handleCheckout}
          variant="contained"
          color="secondary"
          disabled={loading}
          startIcon={loading ? <CircularProgress size={18} color="inherit" /> : <CreditCardIcon />}
        >
          {loading ? 'Capturing Payment...' : `Pay $${total.toFixed(2)}`}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
